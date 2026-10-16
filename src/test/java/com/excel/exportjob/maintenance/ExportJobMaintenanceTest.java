package com.excel.exportjob.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.excel.exportjob.config.ExportExecutorConfig;
import com.excel.exportjob.worker.ExportJobDispatcher;
import com.excel.support.ControllableExportFileGenerator;
import com.excel.support.ControllableGeneratorConfig;
import com.excel.support.IntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 재시작 복구, watchdog, 재제출, 중복 제출 방지.
 * 스케줄러 주기를 길게 두고 각 동작을 테스트에서 직접 호출해 결과를 결정적으로 검증한다.
 */
@IntegrationTest
@Import(ControllableGeneratorConfig.class)
@TestPropertySource(properties = "app.export.maintenance-interval=1h")
class ExportJobMaintenanceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Path EXPORT_DIR = createTempDir();

    @DynamicPropertySource
    static void exportDir(DynamicPropertyRegistry registry) {
        registry.add("app.export.dir", EXPORT_DIR::toString);
    }

    @Autowired
    ExportJobRecovery recovery;

    @Autowired
    ExportJobMaintenance maintenance;

    @Autowired
    ExportJobDispatcher dispatcher;

    @Autowired
    @Qualifier(ExportExecutorConfig.EXPORT_EXECUTOR)
    ThreadPoolTaskExecutor exportExecutor;

    @Autowired
    ControllableExportFileGenerator generator;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        generator.reset();
        jdbcTemplate.execute("TRUNCATE export_job RESTART IDENTITY");
    }

    @AfterEach
    void tearDown() {
        generator.reset();
    }

    @Test
    @DisplayName("재시작 복구: processing 은 시도 횟수가 남으면 pending, 소진되면 failed 로 정리하고 임시 파일을 지운다")
    void recover_resetsInterruptedJobsAndDeletesTempFiles() throws IOException {
        long retryable = insertProcessing(0);
        long exhausted = insertProcessing(2);
        long pending = jdbcTemplate.queryForObject(
                "INSERT INTO export_job DEFAULT VALUES RETURNING id", Long.class);
        Path temp = Files.createFile(EXPORT_DIR.resolve("100.xlsx.tmp"));
        Path completed = Files.createFile(EXPORT_DIR.resolve("101.xlsx"));
        Path poiTempDir = Files.createDirectories(EXPORT_DIR.resolve(ExportJobRecovery.POI_TEMP_DIR));
        Path poiSheet = Files.createFile(poiTempDir.resolve("poi-sxssf-sheet123.xml"));

        int recovered = recovery.recover();

        assertThat(recovered).isEqualTo(2);

        Map<String, Object> retried = rowOf(retryable);
        assertThat(retried.get("status")).isEqualTo("pending");
        assertThat(retried.get("retry_count")).isEqualTo(1);
        assertThat(retried.get("started_at")).isNull();

        Map<String, Object> failed = rowOf(exhausted);
        assertThat(failed.get("status")).isEqualTo("failed");
        assertThat(failed.get("retry_count")).isEqualTo(3);
        assertThat(failed.get("finished_at")).isNotNull();
        assertThat((String) failed.get("error_message")).contains("Interrupted by restart 3 times");

        assertThat(rowOf(pending).get("status")).as("pending 은 건드리지 않는다").isEqualTo("pending");
        assertThat(temp).doesNotExist();
        assertThat(poiSheet).as("생성 도중 종료되며 남은 SXSSF 임시 시트 파일").doesNotExist();
        assertThat(completed).as("완성된 파일은 지우지 않는다").exists();
    }

    @Test
    @DisplayName("watchdog: 제한 시간(10분)을 넘긴 processing 만 failed 로 정리한다")
    void failTimedOut_marksOnlyStaleProcessing() {
        long stale = jdbcTemplate.queryForObject("""
                INSERT INTO export_job (status, started_at)
                VALUES ('processing', now() - INTERVAL '11 minutes') RETURNING id
                """, Long.class);
        long running = insertProcessing(0);

        int failed = maintenance.failTimedOut();

        assertThat(failed).isEqualTo(1);
        assertThat(rowOf(stale).get("status")).isEqualTo("failed");
        assertThat((String) rowOf(stale).get("error_message")).startsWith("Timed out");
        assertThat(rowOf(running).get("status")).isEqualTo("processing");
    }

    @Test
    @DisplayName("재제출: pending 으로 남은 job 을 다시 큐에 넣어 처리한다")
    void resubmitPending_processesLeftoverJobs() {
        generator.succeed();
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("INSERT INTO export_job DEFAULT VALUES");
        }

        int accepted = maintenance.resubmitPending();

        assertThat(accepted).isEqualTo(3);
        await().atMost(TIMEOUT).until(() -> countByStatus("done") == 3);
    }

    @Test
    @DisplayName("이미 큐에 들어간 job 을 다시 제출해도 큐에 중복으로 들어가지 않는다")
    void dispatch_skipsJobAlreadyInFlight() {
        generator.block();
        long running = jdbcTemplate.queryForObject("INSERT INTO export_job DEFAULT VALUES RETURNING id", Long.class);
        long waiting = jdbcTemplate.queryForObject("INSERT INTO export_job DEFAULT VALUES RETURNING id", Long.class);

        assertThat(dispatcher.dispatch(running)).isTrue();
        await().atMost(TIMEOUT).until(() -> "processing".equals(rowOf(running).get("status")));

        assertThat(dispatcher.dispatch(waiting)).isTrue();
        assertThat(dispatcher.dispatch(waiting)).as("이미 큐에 있으면 넣지 않고 true").isTrue();
        assertThat(exportExecutor.getQueueSize()).isEqualTo(1);
        assertThat(dispatcher.inFlightCount()).isEqualTo(2);

        generator.release();
        await().atMost(TIMEOUT).until(() -> countByStatus("done") == 2);
        assertThat(dispatcher.inFlightCount()).isZero();
    }

    private long insertProcessing(int retryCount) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO export_job (status, started_at, retry_count)
                VALUES ('processing', now(), ?) RETURNING id
                """, Long.class, retryCount);
    }

    private Map<String, Object> rowOf(long jobId) {
        return jdbcTemplate.queryForMap("SELECT * FROM export_job WHERE id = ?", jobId);
    }

    private int countByStatus(String status) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM export_job WHERE status = ?", Integer.class, status);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("exports-maintenance");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

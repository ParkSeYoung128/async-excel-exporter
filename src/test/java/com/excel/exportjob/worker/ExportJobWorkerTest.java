package com.excel.exportjob.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.excel.exportjob.application.ExportJobService;
import com.excel.exportjob.application.ExportJobStateService;
import com.excel.support.ControllableExportFileGenerator;
import com.excel.support.ControllableGeneratorConfig;
import com.excel.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
@Import(ControllableGeneratorConfig.class)
class ExportJobWorkerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    ExportJobService exportJobService;

    @Autowired
    ExportJobStateService stateService;

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
    @DisplayName("같은 job 을 두 스레드가 동시에 claim 하면 정확히 하나만 성공한다")
    void claim_isAtomicUnderConcurrency() throws Exception {
        // 서비스를 거치지 않고 직접 넣어 워커가 끼어들지 않게 한다
        long jobId = jdbcTemplate.queryForObject(
                "INSERT INTO export_job DEFAULT VALUES RETURNING id", Long.class);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> claim = () -> {
            start.await();
            return stateService.claim(jobId);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            results.add(pool.submit(claim));
            results.add(pool.submit(claim));
            start.countDown();

            long succeeded = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    succeeded++;
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(statusOf(jobId)).isEqualTo("processing");
        assertThat(stateService.claim(jobId)).as("이미 processing 인 job 은 다시 확보되지 않는다").isFalse();
    }

    @Test
    @DisplayName("성공한 job 은 pending → processing → done 으로 전이되고 시간·결과가 기록된다")
    void process_success_marksDone() {
        generator.succeed();

        long jobId = exportJobService.create().getId();

        await().atMost(TIMEOUT).until(() -> "done".equals(statusOf(jobId)));
        Map<String, Object> row = rowOf(jobId);
        Timestamp startedAt = (Timestamp) row.get("started_at");
        Timestamp finishedAt = (Timestamp) row.get("finished_at");

        assertThat(startedAt).isNotNull();
        assertThat(finishedAt).isNotNull();
        assertThat(startedAt).isBeforeOrEqualTo(finishedAt);
        assertThat((Timestamp) row.get("created_at")).isBeforeOrEqualTo(startedAt);
        assertThat(row.get("file_path")).isEqualTo("/data/exports/" + jobId + ".xlsx");
        assertThat(row.get("row_count")).isEqualTo(ControllableExportFileGenerator.ROW_COUNT);
        assertThat(row.get("error_message")).isNull();
    }

    @Test
    @DisplayName("생성 중 예외가 나면 failed 로 전이되고 에러 메시지와 완료 시간이 기록된다")
    void process_failure_marksFailed() {
        generator.fail();

        long jobId = exportJobService.create().getId();

        await().atMost(TIMEOUT).until(() -> "failed".equals(statusOf(jobId)));
        Map<String, Object> row = rowOf(jobId);

        assertThat(row.get("finished_at")).isNotNull();
        assertThat((String) row.get("error_message")).contains("simulated failure");
        assertThat(row.get("file_path")).isNull();
    }

    private String statusOf(long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM export_job WHERE id = ?", String.class, jobId);
    }

    private Map<String, Object> rowOf(long jobId) {
        return jdbcTemplate.queryForMap("SELECT * FROM export_job WHERE id = ?", jobId);
    }
}

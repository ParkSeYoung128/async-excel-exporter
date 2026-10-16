package com.excel.exportjob.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.excel.support.ControllableExportFileGenerator;
import com.excel.support.ControllableGeneratorConfig;
import com.excel.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 실행 큐가 가득 찬 상황: 큐 용량 0 + 워커가 첫 job 에 붙잡힌 상태에서 두 번째 요청을 보낸다.
 * 거부된 job 은 pending 으로 남았다가, 워커가 비면 재제출 스케줄러(1초 주기)가 다시 넣어 처리된다.
 */
@IntegrationTest
@Import(ControllableGeneratorConfig.class)
@TestPropertySource(properties = {
        "app.export.queue-capacity=0",
        "app.export.maintenance-interval=1s"
})
class ExportJobDispatchRejectionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    WebApplicationContext context;

    @Autowired
    ControllableExportFileGenerator generator;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        generator.reset();
        jdbcTemplate.execute("TRUNCATE export_job RESTART IDENTITY");
    }

    @AfterEach
    void tearDown() {
        generator.reset();
    }

    @Test
    @DisplayName("실행 큐가 가득 차도 요청은 202 로 접수되고, 거부된 job 은 pending 으로 남았다가 재제출되어 처리된다")
    void rejectedDispatch_keepsJobPendingAndReturnsAccepted() throws Exception {
        generator.block();

        mockMvc.perform(post("/api/export-jobs")).andExpect(status().isAccepted());
        await().atMost(TIMEOUT).until(() -> "processing".equals(statusOf(1)));

        // 워커는 job 1 에 붙잡혀 있고 큐 용량은 0 → job 2 의 전달은 거부된다
        mockMvc.perform(post("/api/export-jobs"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(2))
                .andExpect(jsonPath("$.status").value("pending"));
        assertThat(statusOf(2)).isEqualTo("pending");

        generator.release();
        await().atMost(TIMEOUT).until(() -> "done".equals(statusOf(1)));

        // 거부된 job 은 유실되지 않고, 워커가 비면 재제출되어 끝내 처리된다
        await().atMost(TIMEOUT).until(() -> "done".equals(statusOf(2)));
    }

    private String statusOf(long jobId) {
        return jdbcTemplate.queryForObject("SELECT status FROM export_job WHERE id = ?", String.class, jobId);
    }
}

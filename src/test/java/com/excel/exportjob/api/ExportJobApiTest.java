package com.excel.exportjob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.excel.exportjob.worker.ExportJobDispatcher;
import com.excel.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * API 계층만 검증한다. 워커로의 전달은 막아 job 이 pending 으로 유지되게 한다
 * (비동기 처리 검증은 ExportJobWorkerTest).
 */
@IntegrationTest
class ExportJobApiTest {

    @MockitoBean
    ExportJobDispatcher dispatcher;

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbcTemplate;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE export_job RESTART IDENTITY");
    }

    @Test
    @DisplayName("생성 요청은 202 와 Location 을 반환하고 job 을 pending 으로 저장한다")
    void create_returnsAcceptedWithLocation() throws Exception {
        mockMvc.perform(post("/api/export-jobs"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", endsWith("/api/export-jobs/1")))
                .andExpect(jsonPath("$.jobId").value(1))
                .andExpect(jsonPath("$.status").value("pending"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.startedAt").doesNotExist())
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM export_job WHERE id = 1", String.class);
        assertThat(status).isEqualTo("pending");
    }

    @Test
    @DisplayName("단건 조회는 저장된 job 을 반환한다")
    void get_returnsJob() throws Exception {
        mockMvc.perform(post("/api/export-jobs"));

        mockMvc.perform(get("/api/export-jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(1))
                .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    @DisplayName("없는 job 은 404 ProblemDetail 을 반환한다")
    void get_unknownJob_returnsProblemDetail() throws Exception {
        mockMvc.perform(get("/api/export-jobs/999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Export job not found"))
                .andExpect(jsonPath("$.jobId").value(999));
    }

    @Test
    @DisplayName("목록은 최신 요청순으로 페이징된다")
    void list_isLatestFirstAndPaged() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/export-jobs"));
        }

        mockMvc.perform(get("/api/export-jobs").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].jobId").value(3))
                .andExpect(jsonPath("$.content[1].jobId").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));

        mockMvc.perform(get("/api/export-jobs").param("page", "1").param("size", "2"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].jobId").value(1));
    }

    @Test
    @DisplayName("페이지 크기는 최대 100 으로 제한된다")
    void list_clampsPageSize() throws Exception {
        mockMvc.perform(get("/api/export-jobs").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }
}

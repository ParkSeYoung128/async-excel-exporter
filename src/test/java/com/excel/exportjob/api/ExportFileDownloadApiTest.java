package com.excel.exportjob.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.excel.exportjob.maintenance.ExportJobRecovery;
import com.excel.exportjob.worker.ExportJobDispatcher;
import com.excel.support.IntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 파일 다운로드 API. job 과 파일을 직접 만들어 상태·경로별 응답을 검증한다.
 * 워커와 스케줄러는 개입하지 않게 막는다.
 */
@IntegrationTest
@TestPropertySource(properties = "app.export.maintenance-interval=1h")
class ExportFileDownloadApiTest {

    private static final Path EXPORT_DIR = createTempDir("exports-download");
    private static final Path OUTSIDE_DIR = createTempDir("exports-outside");
    private static final byte[] CONTENT = "PK\u0003\u0004fake-xlsx-content".getBytes(StandardCharsets.ISO_8859_1);
    private static final String FINISHED_AT = "2026-10-17 14:30:05";

    @DynamicPropertySource
    static void exportDir(DynamicPropertyRegistry registry) {
        registry.add("app.export.dir", EXPORT_DIR::toString);
    }

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
    @DisplayName("done 작업은 파일을 xlsx 로, 한글 파일명과 함께 내려준다")
    void done_downloadsFile() throws Exception {
        long jobId = insertDoneJob(writeFile(EXPORT_DIR.resolve("1.xlsx")));

        MvcResult result = mockMvc.perform(get("/api/export-jobs/{id}/file", jobId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(ExportJobController.XLSX))
                .andExpect(header().longValue(HttpHeaders.CONTENT_LENGTH, CONTENT.length))
                .andExpect(content().bytes(CONTENT))
                .andReturn();

        String header = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(header).contains("filename*=UTF-8''");
        ContentDisposition disposition = ContentDisposition.parse(header);
        assertThat(disposition.isAttachment()).isTrue();
        assertThat(disposition.getFilename()).isEqualTo("주문내역_1_20261017_143005.xlsx");
    }

    @Test
    @DisplayName("Range 요청은 206 으로 요청한 구간만 내려준다 (이어받기)")
    void range_returnsPartialContent() throws Exception {
        long jobId = insertDoneJob(writeFile(EXPORT_DIR.resolve("1.xlsx")));

        mockMvc.perform(get("/api/export-jobs/{id}/file", jobId).header(HttpHeaders.RANGE, "bytes=0-3"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-3/" + CONTENT.length))
                .andExpect(content().bytes(Arrays.copyOfRange(CONTENT, 0, 4)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "processing", "failed"})
    @DisplayName("done 이 아닌 작업은 409 와 현재 상태를 반환한다")
    void notDone_returnsConflict(String status) throws Exception {
        long jobId = jdbcTemplate.queryForObject(
                "INSERT INTO export_job (status) VALUES (?) RETURNING id", Long.class, status);

        mockMvc.perform(get("/api/export-jobs/{id}/file", jobId))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Export file not ready"))
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value(status));
    }

    @Test
    @DisplayName("없는 작업은 404 를 반환한다")
    void unknownJob_returnsNotFound() throws Exception {
        mockMvc.perform(get("/api/export-jobs/{id}/file", 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Export job not found"));
    }

    @Test
    @DisplayName("done 이지만 파일이 삭제된 경우 404 를 반환한다")
    void missingFile_returnsNotFound() throws Exception {
        long jobId = insertDoneJob(EXPORT_DIR.resolve("deleted.xlsx"));

        mockMvc.perform(get("/api/export-jobs/{id}/file", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Export file not found"))
                .andExpect(jsonPath("$.jobId").value(jobId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"outside", "traversal", "poi-temp", "writing-temp", "symlink"})
    @DisplayName("export 디렉터리 바로 아래의 .xlsx 가 아니면 파일이 있어도 404 를 반환한다")
    void disallowedPath_returnsNotFound(String kind) throws Exception {
        Path path = switch (kind) {
            case "outside" -> writeFile(OUTSIDE_DIR.resolve("secret.xlsx"));
            case "traversal" -> {
                writeFile(OUTSIDE_DIR.resolve("traversal.xlsx"));
                yield EXPORT_DIR.resolve("../" + OUTSIDE_DIR.getFileName() + "/traversal.xlsx");
            }
            case "poi-temp" -> writeFile(EXPORT_DIR.resolve(ExportJobRecovery.POI_TEMP_DIR).resolve("sheet.xlsx"));
            case "writing-temp" -> writeFile(EXPORT_DIR.resolve("2.xlsx.tmp"));
            case "symlink" -> {
                Path target = writeFile(OUTSIDE_DIR.resolve("linked.xlsx"));
                Path link = EXPORT_DIR.resolve("link.xlsx");
                Files.deleteIfExists(link);
                yield Files.createSymbolicLink(link, target);
            }
            default -> throw new IllegalArgumentException(kind);
        };
        long jobId = insertDoneJob(path);

        mockMvc.perform(get("/api/export-jobs/{id}/file", jobId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Export file not found"));
    }

    @Test
    @DisplayName("downloadUrl 은 done 작업에만 내려준다")
    void downloadUrl_onlyWhenDone() throws Exception {
        long doneId = insertDoneJob(writeFile(EXPORT_DIR.resolve("1.xlsx")));
        long pendingId = jdbcTemplate.queryForObject(
                "INSERT INTO export_job (status) VALUES ('pending') RETURNING id", Long.class);

        mockMvc.perform(get("/api/export-jobs/{id}", doneId))
                .andExpect(jsonPath("$.downloadUrl").value("/api/export-jobs/" + doneId + "/file"));
        mockMvc.perform(get("/api/export-jobs/{id}", pendingId))
                .andExpect(jsonPath("$.downloadUrl").doesNotExist());
    }

    private long insertDoneJob(Path filePath) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO export_job (status, started_at, finished_at, file_path, row_count)
                VALUES ('done', CAST(? AS TIMESTAMP) - INTERVAL '3 seconds', CAST(? AS TIMESTAMP), ?, 100000)
                RETURNING id
                """, Long.class, FINISHED_AT, FINISHED_AT, filePath.toString());
    }

    private static Path writeFile(Path path) {
        try {
            Files.createDirectories(path.getParent());
            return Files.write(path, CONTENT);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path createTempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

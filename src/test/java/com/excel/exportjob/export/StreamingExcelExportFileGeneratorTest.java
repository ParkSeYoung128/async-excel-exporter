package com.excel.exportjob.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.excel.exportjob.application.ExportJobService;
import com.excel.exportjob.config.ExportProperties;
import com.excel.support.ExcelSaxReader;
import com.excel.support.ExcelSaxReader.SheetSummary;
import com.excel.support.IntegrationTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 DB(시드 10만 건)와 실제 생성기로 end-to-end 를 검증한다.
 */
@IntegrationTest
class StreamingExcelExportFileGeneratorTest {

    private static final int COLUMNS = 7;
    private static final DateTimeFormatter EXCEL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Path EXPORT_DIR = createTempDir();

    @DynamicPropertySource
    static void exportDir(DynamicPropertyRegistry registry) {
        registry.add("app.export.dir", EXPORT_DIR::toString);
    }

    @Autowired
    ExportJobService exportJobService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("요청부터 done 까지: 파일 행 수와 값이 DB 와 일치하고 숫자·날짜 서식이 적용된다")
    void export_endToEnd_matchesDatabase() throws Exception {
        long jobId = exportJobService.create().getId();

        await().atMost(Duration.ofMinutes(2))
                .until(() -> "done".equals(jdbcTemplate.queryForObject(
                        "SELECT status FROM export_job WHERE id = ?", String.class, jobId)));

        Map<String, Object> job = jdbcTemplate.queryForMap("SELECT * FROM export_job WHERE id = ?", jobId);
        Path file = Path.of((String) job.get("file_path"));
        int dbCount = jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class);

        assertThat(file).exists().hasFileName(jobId + ".xlsx");
        assertThat(job.get("row_count")).isEqualTo(dbCount);
        try (Stream<Path> files = Files.list(EXPORT_DIR)) {
            assertThat(files.map(Path::toString)).noneMatch(name -> name.endsWith(".tmp"));
        }

        List<SheetSummary> sheets = ExcelSaxReader.read(file, COLUMNS);
        assertThat(sheets).hasSize(1);
        SheetSummary sheet = sheets.getFirst();
        assertThat(sheet.name()).isEqualTo("orders");
        assertThat(sheet.dataRows()).isEqualTo(dbCount);
        assertThat(sheet.header()).containsExactly(
                "주문ID", "주문자", "상품명", "카테고리", "결제금액(원)", "주문상태", "주문일시");
        assertThat(sheet.firstDataRow()).isEqualTo(expectedRow(1));
        assertThat(sheet.lastDataRow()).isEqualTo(expectedRow(dbCount));
    }

    @Test
    @DisplayName("생성 도중 실패하면 예외를 던지고 tmp·결과 파일을 남기지 않는다")
    void failureMidStream_leavesNoFiles() throws IOException {
        Path dir = Files.createTempDirectory("exports-failure");
        OrderExportReader failingReader = new OrderExportReader(jdbcTemplate, properties(dir)) {
            @Override
            public int streamAll(Consumer<OrderRow> consumer) {
                for (int i = 1; i <= 10; i++) {
                    consumer.accept(new OrderRow(i, "김민준", "리프트 1일권", "스키", 10_000, "confirmed",
                            LocalDateTime.of(2026, 1, 1, 0, 0)));
                }
                throw new IllegalStateException("db connection lost");
            }
        };
        StreamingExcelExportFileGenerator generator =
                new StreamingExcelExportFileGenerator(failingReader, properties(dir));

        assertThatThrownBy(() -> generator.generate(99L)).hasMessageContaining("db connection lost");
        try (Stream<Path> files = Files.list(dir)) {
            assertThat(files).isEmpty();
        }
    }

    /** DB 값에 엑셀 서식(#,##0 / yyyy-mm-dd hh:mm:ss)을 적용한 기대값 */
    private List<String> expectedRow(int orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT id, user_name, product_name, category, amount, status, order_date FROM orders WHERE id = ?",
                (rs, n) -> List.of(
                        String.valueOf(rs.getInt("id")),
                        rs.getString("user_name"),
                        rs.getString("product_name"),
                        rs.getString("category"),
                        String.format("%,d", rs.getInt("amount")),
                        rs.getString("status"),
                        rs.getObject("order_date", LocalDateTime.class).format(EXCEL_DATE)),
                orderId);
    }

    private static ExportProperties properties(Path dir) {
        return new ExportProperties(dir, 10, 25, 1000);
    }

    private static Path createTempDir() {
        try {
            return Files.createTempDirectory("exports-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

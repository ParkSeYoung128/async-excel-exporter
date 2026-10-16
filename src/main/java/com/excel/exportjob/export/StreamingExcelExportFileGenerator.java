package com.excel.exportjob.export;

import com.excel.exportjob.config.ExportProperties;
import com.excel.exportjob.worker.ExportFileGenerator;
import com.excel.exportjob.worker.ExportResult;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 주문 전체를 스트리밍으로 읽어 엑셀 파일을 만든다. 메모리 사용량이 데이터 건수와 무관하게 일정하다.
 * <ul>
 *   <li>SXSSF(행 창 {@value #ROW_ACCESS_WINDOW}): 최근 N행만 메모리에 두고 나머지는 디스크 임시 파일로 내보낸다.
 *       대가로 이미 내보낸 행은 수정할 수 없다.</li>
 *   <li>공유 문자열 표 미사용: 공유 문자열 표는 서로 다른 문자열이 늘수록 메모리에 계속 쌓인다.
 *       셀에 문자열을 직접 넣어 메모리를 일정하게 유지한다 (대가: 파일 크기 소폭 증가).</li>
 *   <li>임시 파일 압축 미사용: 0.5 CPU 환경에서는 디스크보다 CPU 가 더 귀하다.</li>
 *   <li>원자적 완료: {jobId}.xlsx.tmp 에 다 쓴 뒤 같은 디렉터리 안에서 이름을 바꾼다.
 *       done 인데 파일이 없거나 쓰다 만 파일이 보이는 상태가 생기지 않는다.</li>
 * </ul>
 */
@Component
public class StreamingExcelExportFileGenerator implements ExportFileGenerator {

    private static final Logger log = LoggerFactory.getLogger(StreamingExcelExportFileGenerator.class);
    static final int ROW_ACCESS_WINDOW = 100;

    private final OrderExportReader reader;
    private final Path exportDir;

    public StreamingExcelExportFileGenerator(OrderExportReader reader, ExportProperties properties) {
        this.reader = reader;
        this.exportDir = properties.dir();
    }

    @Override
    public ExportResult generate(long jobId) {
        Path target = exportDir.resolve(jobId + ".xlsx");
        Path tmp = exportDir.resolve(jobId + ".xlsx.tmp");

        SXSSFWorkbook workbook = new SXSSFWorkbook(null, ROW_ACCESS_WINDOW, false, false);
        try {
            Files.createDirectories(exportDir);

            OrderSheetWriter writer = new OrderSheetWriter(workbook);
            int rowCount = reader.streamAll(writer::write);
            writer.finish();

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
                workbook.write(out);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return new ExportResult(target.toString(), rowCount);
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("Failed to write export file for job " + jobId, e);
        } catch (RuntimeException e) {
            deleteQuietly(tmp);
            throw e;
        } finally {
            release(workbook);
        }
    }

    /** SXSSF 가 디스크에 내보낸 임시 시트 파일을 성공·실패와 무관하게 지운다. */
    @SuppressWarnings("deprecation")
    private static void release(SXSSFWorkbook workbook) {
        workbook.dispose();
        try {
            workbook.close();
        } catch (IOException e) {
            log.warn("Failed to close workbook", e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temporary export file {}", path, e);
        }
    }
}

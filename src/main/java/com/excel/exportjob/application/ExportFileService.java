package com.excel.exportjob.application;

import com.excel.exportjob.config.ExportProperties;
import com.excel.exportjob.domain.ExportJob;
import com.excel.exportjob.domain.ExportJobStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 완료된 작업의 엑셀 파일을 내려받을 수 있는지 판단한다.
 * <p>
 * DB 의 file_path 를 그대로 신뢰하지 않는다. export 디렉터리 바로 아래의 .xlsx 만 허용해
 * 생성 중인 임시 파일(*.xlsx.tmp), POI 임시 디렉터리(.poi-tmp), 디렉터리 밖 경로가 노출되지 않게 한다.
 */
@Service
public class ExportFileService {

    private static final Logger log = LoggerFactory.getLogger(ExportFileService.class);
    private static final String EXTENSION = ".xlsx";
    private static final String DOWNLOAD_NAME_PREFIX = "주문내역_";
    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ExportJobService exportJobService;
    private final Path exportDir;

    public ExportFileService(ExportJobService exportJobService, ExportProperties properties) {
        this.exportJobService = exportJobService;
        this.exportDir = properties.dir().toAbsolutePath().normalize();
    }

    public ExportFile prepareDownload(long jobId) {
        ExportJob job = exportJobService.get(jobId);
        if (job.getStatus() != ExportJobStatus.DONE) {
            throw new ExportFileNotReadyException(jobId, job.getStatus());
        }
        return new ExportFile(resolveFile(job), downloadName(job));
    }

    private Path resolveFile(ExportJob job) {
        long jobId = job.getId();
        Path path = toAllowedPath(job);
        try {
            // 심볼릭 링크로 디렉터리 밖을 가리키는 경우까지 막기 위해 실제 경로로 한 번 더 확인한다
            Path real = path.toRealPath();
            if (!isAllowed(real, exportDir.toRealPath())
                    || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("Refusing to serve export file: jobId={}, path={}", jobId, real);
                throw new ExportFileNotFoundException(jobId);
            }
            return real;
        } catch (NoSuchFileException e) {
            log.warn("Export file is missing: jobId={}, path={}", jobId, path);
            throw new ExportFileNotFoundException(jobId);
        } catch (IOException e) {
            log.warn("Failed to resolve export file: jobId={}, path={}", jobId, path, e);
            throw new ExportFileNotFoundException(jobId);
        }
    }

    private Path toAllowedPath(ExportJob job) {
        try {
            Path path = Path.of(job.getFilePath()).toAbsolutePath().normalize();
            if (isAllowed(path, exportDir)) {
                return path;
            }
            log.warn("Refusing to serve export file outside export directory: jobId={}, path={}",
                    job.getId(), path);
        } catch (InvalidPathException e) {
            log.warn("Invalid export file path: jobId={}, path={}", job.getId(), job.getFilePath());
        }
        throw new ExportFileNotFoundException(job.getId());
    }

    private static boolean isAllowed(Path path, Path dir) {
        return dir.equals(path.getParent())
                && path.getFileName().toString().endsWith(EXTENSION);
    }

    private static String downloadName(ExportJob job) {
        if (job.getFinishedAt() == null) {
            return DOWNLOAD_NAME_PREFIX + job.getId() + EXTENSION;
        }
        return DOWNLOAD_NAME_PREFIX + job.getId() + "_" + job.getFinishedAt().format(NAME_TIME) + EXTENSION;
    }
}

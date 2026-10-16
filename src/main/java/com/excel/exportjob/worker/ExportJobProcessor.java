package com.excel.exportjob.worker;

import com.excel.exportjob.application.ExportJobStateService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * export 스레드에서 job 하나를 처리한다: claim → 생성 → done / failed.
 */
@Component
public class ExportJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ExportJobProcessor.class);

    private final ExportJobStateService stateService;
    private final ExportFileGenerator generator;

    public ExportJobProcessor(ExportJobStateService stateService, ExportFileGenerator generator) {
        this.stateService = stateService;
        this.generator = generator;
    }

    public void process(long jobId) {
        if (!stateService.claim(jobId)) {
            // 중복 제출이거나 이미 처리된 job. 정상적인 경로이므로 조용히 건너뛴다.
            log.debug("Export job {} is not pending. Skipped", jobId);
            return;
        }

        long startedAt = System.nanoTime();
        log.info("Export job {} started", jobId);
        try {
            ExportResult result = generator.generate(jobId);
            stateService.markDone(jobId, result.filePath(), result.rowCount());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
            log.info("Export job {} done: rows={}, elapsed={}ms, rowsPerSec={}",
                    jobId, result.rowCount(), elapsed.toMillis(), rowsPerSecond(result.rowCount(), elapsed));
        } catch (Exception e) {
            // Error(OOM 등)는 잡지 않는다: JVM 상태를 신뢰할 수 없으므로 프로세스 재시작 후 복구(#11)에 맡긴다.
            log.error("Export job {} failed", jobId, e);
            stateService.markFailed(jobId, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static long rowsPerSecond(int rows, Duration elapsed) {
        long millis = Math.max(elapsed.toMillis(), 1);
        return rows * 1000L / millis;
    }
}

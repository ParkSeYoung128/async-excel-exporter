package com.excel.exportjob.worker;

import com.excel.exportjob.application.ExportJobStateService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * export 스레드에서 job 하나를 처리한다: claim → 생성 → done / failed.
 */
@Component
public class ExportJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ExportJobProcessor.class);

    private final ExportJobStateService stateService;
    private final ExportFileGenerator generator;
    private final Timer doneTimer;
    private final Timer failedTimer;
    private final Counter exportedRows;

    private volatile boolean shuttingDown;

    public ExportJobProcessor(ExportJobStateService stateService, ExportFileGenerator generator,
                              MeterRegistry meterRegistry) {
        this.stateService = stateService;
        this.generator = generator;
        // /actuator/metrics/export.duration?tag=outcome:done 으로 생성 시간 분포를 확인한다
        this.doneTimer = Timer.builder("export.duration")
                .description("Excel export duration")
                .tag("outcome", "done")
                .register(meterRegistry);
        this.failedTimer = Timer.builder("export.duration")
                .description("Excel export duration")
                .tag("outcome", "failed")
                .register(meterRegistry);
        this.exportedRows = Counter.builder("export.rows")
                .description("Rows written to Excel files")
                .register(meterRegistry);
    }

    /**
     * 종료가 시작되면 큐에 남아 있던 작업은 새로 시작하지 않는다.
     * claim 전에 건너뛰므로 job 은 pending 으로 남고, 재시작 후 재제출된다.
     */
    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        shuttingDown = true;
    }

    public void process(long jobId) {
        if (shuttingDown) {
            log.info("Shutting down. Export job {} stays pending", jobId);
            return;
        }

        boolean claimed;
        try {
            claimed = stateService.claim(jobId);
        } catch (Exception e) {
            // claim 이 실패하면 상태는 바뀌지 않았으므로 job 은 pending 으로 남는다
            log.error("Failed to claim export job {}. It stays pending", jobId, e);
            return;
        }
        if (!claimed) {
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
            doneTimer.record(elapsed);
            exportedRows.increment(result.rowCount());
            log.info("Export job {} done: rows={}, elapsed={}ms, rowsPerSec={}",
                    jobId, result.rowCount(), elapsed.toMillis(), rowsPerSecond(result.rowCount(), elapsed));
        } catch (Exception e) {
            // Error(OOM 등)는 잡지 않는다: JVM 상태를 신뢰할 수 없으므로 프로세스 재시작 후 복구(#11)에 맡긴다.
            failedTimer.record(Duration.ofNanos(System.nanoTime() - startedAt));
            log.error("Export job {} failed", jobId, e);
            markFailedSafely(jobId, e);
        }
    }

    /**
     * 실패 기록까지 실패해도(DB 장애 등) 예외를 executor 로 전파하지 않고 로그로 남긴다.
     * 이 경우 job 은 processing 에 남으며, 재시작 복구(#11)가 정리한다.
     */
    private void markFailedSafely(long jobId, Exception cause) {
        try {
            stateService.markFailed(jobId, cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } catch (Exception e) {
            e.addSuppressed(cause);
            log.error("Failed to mark export job {} as failed. It stays processing until recovery", jobId, e);
        }
    }

    private static long rowsPerSecond(int rows, Duration elapsed) {
        long millis = Math.max(elapsed.toMillis(), 1);
        return rows * 1000L / millis;
    }
}

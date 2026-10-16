package com.excel.exportjob.maintenance;

import com.excel.exportjob.application.ExportJobStateService;
import com.excel.exportjob.config.ExportProperties;
import com.excel.exportjob.domain.ExportJobRepository;
import com.excel.exportjob.worker.ExportJobDispatcher;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 주기적으로 실행되는 job 정리 작업.
 * <ol>
 *   <li>watchdog: 제한 시간을 넘긴 processing 을 failed 로 정리</li>
 *   <li>재제출: 큐 거부·재시작 등으로 pending 에 남은 job 을 다시 실행 큐에 넣음</li>
 * </ol>
 */
@Component
public class ExportJobMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ExportJobMaintenance.class);

    private final ExportJobStateService stateService;
    private final ExportJobRepository exportJobRepository;
    private final ExportJobDispatcher dispatcher;
    private final ExportProperties properties;

    public ExportJobMaintenance(ExportJobStateService stateService, ExportJobRepository exportJobRepository,
                                ExportJobDispatcher dispatcher, ExportProperties properties) {
        this.stateService = stateService;
        this.exportJobRepository = exportJobRepository;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    public void runOnce() {
        failTimedOut();
        resubmitPending();
    }

    public int failTimedOut() {
        int failed = stateService.failTimedOut(properties.processingTimeout());
        if (failed > 0) {
            log.warn("Marked {} export job(s) as failed: processing exceeded {}", failed, properties.processingTimeout());
        }
        return failed;
    }

    /**
     * 오래된 pending 부터 큐에 넣고, 큐가 거부하면 이번 주기는 멈춘다 (큐 빈 자리만큼만 제출).
     * 이미 큐·실행 중인 job 은 dispatcher 가 건너뛰므로, 그만큼 더 조회해 새 job 까지 닿게 한다.
     *
     * @return 이번 주기에 실행 예정으로 넘긴 job 수
     */
    public int resubmitPending() {
        int limit = properties.queueCapacity() + 1 + dispatcher.inFlightCount();
        List<Long> pendingIds = exportJobRepository.findPendingIds(Math.max(limit, 1));
        int accepted = 0;
        for (Long jobId : pendingIds) {
            if (!dispatcher.dispatch(jobId)) {
                break;
            }
            accepted++;
        }
        if (accepted > 0) {
            log.debug("Resubmitted {} pending export job(s)", accepted);
        }
        return accepted;
    }
}

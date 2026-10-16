package com.excel.exportjob.worker;

import com.excel.exportjob.config.ExportExecutorConfig;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

@Component
public class LocalExecutorExportJobDispatcher implements ExportJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalExecutorExportJobDispatcher.class);

    private final TaskExecutor exportExecutor;
    private final ExportJobProcessor processor;

    /**
     * 큐에 들어갔거나 실행 중인 job. 재제출 스케줄러가 큐에서 기다리는 job 을 매 주기 다시 넣어
     * 큐가 중복으로 채워지는 것을 막는다. (그래도 중복 처리는 claim 이 최종적으로 막는다)
     */
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public LocalExecutorExportJobDispatcher(
            @Qualifier(ExportExecutorConfig.EXPORT_EXECUTOR) TaskExecutor exportExecutor,
            ExportJobProcessor processor) {
        this.exportExecutor = exportExecutor;
        this.processor = processor;
    }

    /**
     * 큐 거부 예외를 호출자에게 전파하지 않는다.
     * AFTER_COMMIT 리스너에서 예외가 전파되면 job 은 이미 커밋됐는데 API 는 실패 응답을 받는
     * 정합성 문제가 생긴다. 거부된 job 은 pending 으로 남고 재제출 스케줄러가 다시 넣는다.
     */
    @Override
    public boolean dispatch(long jobId) {
        if (!inFlight.add(jobId)) {
            return true;
        }
        try {
            exportExecutor.execute(() -> {
                try {
                    processor.process(jobId);
                } finally {
                    inFlight.remove(jobId);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            inFlight.remove(jobId);
            log.warn("Export queue is full. Job {} stays pending until resubmitted", jobId);
            return false;
        }
    }

    @Override
    public int inFlightCount() {
        return inFlight.size();
    }
}

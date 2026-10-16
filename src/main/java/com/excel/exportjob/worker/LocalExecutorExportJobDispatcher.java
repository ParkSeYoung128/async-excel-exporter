package com.excel.exportjob.worker;

import com.excel.exportjob.config.ExportExecutorConfig;
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
        try {
            exportExecutor.execute(() -> processor.process(jobId));
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("Export queue is full. Job {} stays pending until resubmitted", jobId);
            return false;
        }
    }
}

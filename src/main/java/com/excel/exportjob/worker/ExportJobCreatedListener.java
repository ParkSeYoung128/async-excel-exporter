package com.excel.exportjob.worker;

import com.excel.exportjob.application.ExportJobCreatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class ExportJobCreatedListener {

    private final ExportJobDispatcher dispatcher;

    public ExportJobCreatedListener(ExportJobDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * 커밋 후에만 전달한다. 커밋 전에 전달하면 워커가 아직 보이지 않는 job 을 claim 하려다 실패할 수 있고,
     * 롤백된 job 이 처리될 수도 있다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(ExportJobCreatedEvent event) {
        dispatcher.dispatch(event.jobId());
    }
}

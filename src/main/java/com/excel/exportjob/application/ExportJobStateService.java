package com.excel.exportjob.application;

import com.excel.exportjob.domain.ExportJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * job 상태 전이. 전이마다 짧은 트랜잭션으로 즉시 커밋해 목록 화면에 바로 반영되게 한다.
 * 워커(ExportJobProcessor)와 다른 빈으로 분리한 이유: 같은 빈 안에서 호출하면 프록시를 거치지 않아
 * {@code @Transactional} 이 적용되지 않는다 (self-invocation).
 */
@Service
public class ExportJobStateService {

    private static final Logger log = LoggerFactory.getLogger(ExportJobStateService.class);
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final ExportJobRepository exportJobRepository;

    public ExportJobStateService(ExportJobRepository exportJobRepository) {
        this.exportJobRepository = exportJobRepository;
    }

    /**
     * pending 인 job 을 processing 으로 확보한다.
     *
     * @return 이 호출이 확보에 성공했으면 true. 이미 다른 곳에서 확보했거나 pending 이 아니면 false
     */
    @Transactional
    public boolean claim(long jobId) {
        return exportJobRepository.claim(jobId) == 1;
    }

    @Transactional
    public void markDone(long jobId, String filePath, int rowCount) {
        if (exportJobRepository.markDone(jobId, filePath, rowCount) == 0) {
            log.warn("Export job {} was not in processing state when marking done", jobId);
        }
    }

    @Transactional
    public void markFailed(long jobId, String errorMessage) {
        if (exportJobRepository.markFailed(jobId, truncate(errorMessage)) == 0) {
            log.warn("Export job {} was not in processing state when marking failed", jobId);
        }
    }

    private static String truncate(String message) {
        if (message == null || message.length() <= MAX_ERROR_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }
}

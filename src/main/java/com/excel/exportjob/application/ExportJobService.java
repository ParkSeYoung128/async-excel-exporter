package com.excel.exportjob.application;

import com.excel.exportjob.domain.ExportJob;
import com.excel.exportjob.domain.ExportJobRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExportJobService {

    public static final int MAX_PAGE_SIZE = 100;

    // created_at 이 같을 수 있으므로 id 로 순서를 확정한다 (페이지 경계에서 중복·누락 방지)
    private static final Sort LATEST_FIRST = Sort.by(
            Sort.Order.desc("createdAt"),
            Sort.Order.desc("id"));

    private final ExportJobRepository exportJobRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ExportJobService(ExportJobRepository exportJobRepository, ApplicationEventPublisher eventPublisher) {
        this.exportJobRepository = exportJobRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 작업을 pending 으로 등록만 하고 즉시 반환한다. 실제 생성은 백그라운드 워커가 처리한다.
     * 이벤트는 커밋 후에 전달되므로, 워커가 아직 커밋되지 않은 job 을 조회하는 일이 없다.
     * 서버 측 중복 제거는 하지 않는다: 요청마다 개별 job 으로 대기열에 쌓인다.
     */
    @Transactional
    public ExportJob create() {
        ExportJob job = exportJobRepository.save(ExportJob.pending());
        eventPublisher.publishEvent(new ExportJobCreatedEvent(job.getId()));
        return job;
    }

    @Transactional(readOnly = true)
    public ExportJob get(long jobId) {
        return exportJobRepository.findById(jobId)
                .orElseThrow(() -> new ExportJobNotFoundException(jobId));
    }

    @Transactional(readOnly = true)
    public Page<ExportJob> list(int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        return exportJobRepository.findAll(PageRequest.of(safePage, safeSize, LATEST_FIRST));
    }
}

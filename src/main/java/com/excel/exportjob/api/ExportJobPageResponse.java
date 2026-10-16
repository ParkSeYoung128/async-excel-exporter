package com.excel.exportjob.api;

import com.excel.exportjob.domain.ExportJob;
import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Spring Data {@link Page} 를 그대로 직렬화하면 JSON 형식이 버전마다 달라질 수 있어 고정 형식으로 감싼다.
 */
public record ExportJobPageResponse(
        List<ExportJobResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static ExportJobPageResponse from(Page<ExportJob> page) {
        return new ExportJobPageResponse(
                page.getContent().stream().map(ExportJobResponse::from).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}

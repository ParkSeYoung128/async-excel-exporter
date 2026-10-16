package com.excel.exportjob.api;

import com.excel.exportjob.domain.ExportJob;
import java.time.LocalDateTime;

/**
 * 시간은 KST(Asia/Seoul) 기준 LocalDateTime 을 ISO-8601 문자열로 응답한다.
 */
public record ExportJobResponse(
        Long jobId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String filePath,
        Integer rowCount,
        String errorMessage
) {

    public static ExportJobResponse from(ExportJob job) {
        return new ExportJobResponse(
                job.getId(),
                job.getStatus().dbValue(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getFilePath(),
                job.getRowCount(),
                job.getErrorMessage());
    }
}

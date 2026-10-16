package com.excel.exportjob.api;

import com.excel.exportjob.domain.ExportJob;
import com.excel.exportjob.domain.ExportJobStatus;
import java.time.LocalDateTime;

/**
 * 시간은 KST(Asia/Seoul) 기준 LocalDateTime 을 ISO-8601 문자열로 응답한다.
 * downloadUrl 은 done 일 때만 값이 있다 (클라이언트가 URL 규칙을 몰라도 되도록 서버가 제공).
 */
public record ExportJobResponse(
        Long jobId,
        String status,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        String filePath,
        Integer rowCount,
        String errorMessage,
        String downloadUrl
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
                job.getErrorMessage(),
                downloadUrl(job));
    }

    private static String downloadUrl(ExportJob job) {
        if (job.getStatus() != ExportJobStatus.DONE) {
            return null;
        }
        return "/api/export-jobs/" + job.getId() + "/file";
    }
}

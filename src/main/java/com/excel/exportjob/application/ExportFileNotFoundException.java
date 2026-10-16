package com.excel.exportjob.application;

/**
 * 작업은 완료됐지만 내려줄 수 있는 파일이 없다 (삭제됐거나, 허용되지 않은 경로를 가리킴).
 * 경로 정보 노출을 막기 위해 원인은 구분하지 않고 서버 로그에만 남긴다.
 */
public class ExportFileNotFoundException extends RuntimeException {

    private final long jobId;

    public ExportFileNotFoundException(long jobId) {
        super("Export file not found for job: " + jobId);
        this.jobId = jobId;
    }

    public long getJobId() {
        return jobId;
    }
}

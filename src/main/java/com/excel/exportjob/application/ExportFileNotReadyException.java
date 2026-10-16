package com.excel.exportjob.application;

import com.excel.exportjob.domain.ExportJobStatus;

/** 작업이 아직 완료(done)되지 않아 내려받을 파일이 없다. */
public class ExportFileNotReadyException extends RuntimeException {

    private final long jobId;
    private final ExportJobStatus status;

    public ExportFileNotReadyException(long jobId, ExportJobStatus status) {
        super("Export file is not ready: job " + jobId + " is " + status.dbValue());
        this.jobId = jobId;
        this.status = status;
    }

    public long getJobId() {
        return jobId;
    }

    public ExportJobStatus getStatus() {
        return status;
    }
}

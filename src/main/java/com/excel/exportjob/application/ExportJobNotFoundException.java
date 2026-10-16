package com.excel.exportjob.application;

public class ExportJobNotFoundException extends RuntimeException {

    private final long jobId;

    public ExportJobNotFoundException(long jobId) {
        super("Export job not found: " + jobId);
        this.jobId = jobId;
    }

    public long getJobId() {
        return jobId;
    }
}

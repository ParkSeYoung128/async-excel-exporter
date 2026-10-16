package com.excel.exportjob.domain;

import java.util.Arrays;

/**
 * 엑셀 생성 작업 상태. 전이: PENDING -> PROCESSING -> DONE / FAILED
 * <p>
 * DB 의 CHECK 제약은 소문자 값만 허용하므로 enum 이름 대신 {@link #dbValue} 를 저장한다.
 */
public enum ExportJobStatus {

    PENDING("pending"),
    PROCESSING("processing"),
    DONE("done"),
    FAILED("failed");

    private final String dbValue;

    ExportJobStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static ExportJobStatus fromDbValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown export job status: " + value));
    }
}

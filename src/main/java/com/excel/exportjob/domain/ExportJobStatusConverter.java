package com.excel.exportjob.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link ExportJobStatus} <-> DB 소문자 문자열 변환.
 * {@code @Enumerated(STRING)} 은 "PENDING" 을 저장해 DB CHECK 제약에 걸리므로 직접 변환한다.
 */
@Converter(autoApply = true)
public class ExportJobStatusConverter implements AttributeConverter<ExportJobStatus, String> {

    @Override
    public String convertToDatabaseColumn(ExportJobStatus status) {
        return status == null ? null : status.dbValue();
    }

    @Override
    public ExportJobStatus convertToEntityAttribute(String value) {
        return value == null ? null : ExportJobStatus.fromDbValue(value);
    }
}

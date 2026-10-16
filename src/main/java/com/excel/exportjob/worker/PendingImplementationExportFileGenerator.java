package com.excel.exportjob.worker;

import org.springframework.stereotype.Component;

/**
 * 실제 스트리밍 생성기(#10)가 들어오기 전까지의 임시 구현.
 * job 이 pending/processing 에 멈추지 않고 failed 로 끝나게 해 중간 상태도 정직하게 드러낸다.
 */
@Component
public class PendingImplementationExportFileGenerator implements ExportFileGenerator {

    @Override
    public ExportResult generate(long jobId) {
        throw new UnsupportedOperationException("Excel generation is not implemented yet (#10)");
    }
}

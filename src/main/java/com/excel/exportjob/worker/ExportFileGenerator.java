package com.excel.exportjob.worker;

/**
 * job 하나에 대한 엑셀 파일을 생성한다. 실패는 unchecked 예외로 알린다.
 */
public interface ExportFileGenerator {

    ExportResult generate(long jobId);
}

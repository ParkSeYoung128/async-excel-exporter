package com.excel.exportjob.worker;

/**
 * @param filePath 저장된 파일 경로
 * @param rowCount 파일에 기록된 데이터 행 수 (헤더 제외)
 */
public record ExportResult(String filePath, int rowCount) {
}

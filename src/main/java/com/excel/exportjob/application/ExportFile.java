package com.excel.exportjob.application;

import java.nio.file.Path;

/**
 * 다운로드할 엑셀 파일.
 *
 * @param path         검증을 마친 실제 파일 경로 (export 디렉터리 바로 아래의 .xlsx)
 * @param downloadName 사용자에게 보여 줄 파일명
 */
public record ExportFile(Path path, String downloadName) {
}

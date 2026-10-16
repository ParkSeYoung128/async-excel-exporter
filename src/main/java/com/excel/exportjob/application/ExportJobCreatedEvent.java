package com.excel.exportjob.application;

/**
 * job 생성 트랜잭션 안에서 발행되고, 커밋된 뒤에만 워커로 전달된다 (AFTER_COMMIT).
 */
public record ExportJobCreatedEvent(long jobId) {
}

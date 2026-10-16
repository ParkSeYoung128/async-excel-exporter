package com.excel.exportjob.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 엑셀 생성 관련 설정 (app.export.*).
 *
 * @param dir           엑셀 파일 저장 디렉터리 (컨테이너 로컬 볼륨)
 * @param queueCapacity 실행 대기 큐 크기. 진짜 대기열은 DB(pending)이므로 메모리 큐는 작게 둔다.
 *                      넘친 작업은 pending 으로 남아 재제출 스케줄러가 다시 넣는다.
 */
@ConfigurationProperties(prefix = "app.export")
public record ExportProperties(
        @DefaultValue("/data/exports") Path dir,
        @DefaultValue("10") int queueCapacity
) {
}

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
 * @param shutdownAwaitSeconds 종료 시 실행 중인 작업을 기다리는 최대 시간(초).
 *                      Tomcat graceful 종료(spring.lifecycle 30초) + 이 값이
 *                      compose stop_grace_period(60초) 안에 들어와야 SIGKILL 전에 끝난다.
 */
@ConfigurationProperties(prefix = "app.export")
public record ExportProperties(
        @DefaultValue("/data/exports") Path dir,
        @DefaultValue("10") int queueCapacity,
        @DefaultValue("25") int shutdownAwaitSeconds
) {

    /**
     * 음수 대기 시간은 executor 가 예외 없이 "대기 안 함"으로 처리해 종료 대기 계약이 조용히 깨지므로 기동 시점에 거부한다.
     * 0 은 "대기하지 않음"을 뜻하는 유효한 설정으로 허용한다.
     * queueCapacity 는 0 이하를 SynchronousQueue(대기 큐 없음)로 처리하는 지원 설정이라 검증하지 않는다.
     */
    public ExportProperties {
        if (shutdownAwaitSeconds < 0) {
            throw new IllegalArgumentException(
                    "app.export.shutdown-await-seconds must be >= 0, but was " + shutdownAwaitSeconds);
        }
    }
}

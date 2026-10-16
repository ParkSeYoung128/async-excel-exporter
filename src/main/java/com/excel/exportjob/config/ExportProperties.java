package com.excel.exportjob.config;

import java.nio.file.Path;
import java.time.Duration;
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
 * @param fetchSize     주문 스트리밍 조회 시 DB 에서 한 번에 가져오는 행 수.
 *                      작으면 왕복이 늘고, 크면 메모리 피크가 커진다.
 * @param maintenanceInterval  pending 재제출·장기 processing 정리 주기
 * @param processingTimeout    processing 이 이 시간을 넘으면 failed 로 정리한다 (워커 정지 대비)
 * @param queryTimeout         엑셀 생성 조회 중 DB 명령 하나가 응답 없이 걸릴 수 있는 최대 시간
 * @param maxAttempts          재시작으로 중단된 job 을 다시 시도하는 최대 횟수 (OOM 등으로 매번 죽는 job 의 무한 반복 방지)
 */
@ConfigurationProperties(prefix = "app.export")
public record ExportProperties(
        @DefaultValue("/data/exports") Path dir,
        @DefaultValue("10") int queueCapacity,
        @DefaultValue("25") int shutdownAwaitSeconds,
        @DefaultValue("1000") int fetchSize,
        @DefaultValue("10s") Duration maintenanceInterval,
        @DefaultValue("10m") Duration processingTimeout,
        @DefaultValue("5m") Duration queryTimeout,
        @DefaultValue("3") int maxAttempts
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
        // pgjdbc 에서 fetchSize 0 은 "전체를 한 번에 가져오기"라 스트리밍이 무력화되고 10만 건이 메모리에 올라간다
        if (fetchSize <= 0) {
            throw new IllegalArgumentException("app.export.fetch-size must be > 0, but was " + fetchSize);
        }
        requirePositive("maintenance-interval", maintenanceInterval);
        requirePositive("processing-timeout", processingTimeout);
        requirePositive("query-timeout", queryTimeout);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("app.export.max-attempts must be >= 1, but was " + maxAttempts);
        }
    }

    private static void requirePositive(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.export." + name + " must be positive, but was " + value);
        }
    }
}

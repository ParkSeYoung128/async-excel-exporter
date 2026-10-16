package com.excel.exportjob.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExportPropertiesTest {

    private static final Path DIR = Path.of("/data/exports");

    @Test
    @DisplayName("음수 종료 대기 시간은 거부한다")
    void negativeShutdownAwait_isRejected() {
        assertThatThrownBy(() -> properties(10, -1, 1000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shutdown-await-seconds");
    }

    @Test
    @DisplayName("종료 대기 0초와 큐 용량 0은 허용한다")
    void zeroValues_areAllowed() {
        assertThatCode(() -> properties(0, 0, 1000)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("fetchSize 0 이하는 스트리밍을 무력화하므로 거부한다")
    void nonPositiveFetchSize_isRejected() {
        assertThatThrownBy(() -> properties(10, 25, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fetch-size");
    }

    @Test
    @DisplayName("주기·타임아웃은 양수, 최대 시도 횟수는 1 이상이어야 한다")
    void maintenanceSettings_mustBePositive() {
        assertThatThrownBy(() -> new ExportProperties(DIR, 10, 25, 1000,
                Duration.ZERO, Duration.ofMinutes(10), Duration.ofMinutes(5), 3))
                .hasMessageContaining("maintenance-interval");
        assertThatThrownBy(() -> new ExportProperties(DIR, 10, 25, 1000,
                Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofMinutes(5), 0))
                .hasMessageContaining("max-attempts");
    }

    static ExportProperties properties(int queueCapacity, int shutdownAwaitSeconds, int fetchSize) {
        return new ExportProperties(DIR, queueCapacity, shutdownAwaitSeconds, fetchSize,
                Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofMinutes(5), 3);
    }
}

package com.excel.exportjob.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExportPropertiesTest {

    private static final Path DIR = Path.of("/data/exports");

    @Test
    @DisplayName("음수 종료 대기 시간은 거부한다")
    void negativeShutdownAwait_isRejected() {
        assertThatThrownBy(() -> new ExportProperties(DIR, 10, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shutdown-await-seconds");
    }

    @Test
    @DisplayName("종료 대기 0초와 큐 용량 0은 허용한다")
    void zeroValues_areAllowed() {
        assertThatCode(() -> new ExportProperties(DIR, 0, 0)).doesNotThrowAnyException();
    }
}

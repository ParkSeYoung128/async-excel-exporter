package com.excel.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.excel.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class DatabaseTimeZoneTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("애플리케이션 DB 세션 시간대는 JVM 시간대와 무관하게 Asia/Seoul 이다")
    void sessionTimeZoneIsSeoul() {
        String timeZone = jdbcTemplate.queryForObject("SHOW TIME ZONE", String.class);
        assertThat(timeZone).isEqualTo("Asia/Seoul");
    }
}

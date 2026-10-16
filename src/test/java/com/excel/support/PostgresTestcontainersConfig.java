package com.excel.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 운영(docker-compose)과 같은 PostgreSQL 이미지와 같은 init SQL(db/init)로 테스트 DB 를 띄운다.
 * 스키마 정의가 한 곳(db/init)에만 존재하므로 테스트와 운영의 스키마가 어긋나지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withEnv("TZ", "Asia/Seoul")
                .withEnv("PGTZ", "Asia/Seoul")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("db/init"),
                        "/docker-entrypoint-initdb.d/");
    }
}

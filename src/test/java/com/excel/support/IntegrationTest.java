package com.excel.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 실제 PostgreSQL(Testcontainers)을 쓰는 통합 테스트용 메타 애너테이션.
 * 같은 설정을 쓰는 테스트끼리 스프링 컨텍스트와 컨테이너를 재사용한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@Import(PostgresTestcontainersConfig.class)
public @interface IntegrationTest {
}

package com.excel.exportjob.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExportProperties.class)
public class ExportExecutorConfig {

    public static final String EXPORT_EXECUTOR = "exportExecutor";

    /**
     * 엑셀 생성 전용 executor.
     * <ul>
     *   <li>스레드 1개: CPU 0.5 에서 병렬화해도 처리량은 늘지 않고 메모리 피크·DB 커넥션 점유만 늘어난다.</li>
     *   <li>큐 상한: 무제한 큐(@Async 기본값)는 요청 폭주 시 메모리를 잠식한다. 진짜 대기열은 DB 다.</li>
     *   <li>AbortPolicy: 큐가 차면 즉시 거부해 호출자(dispatcher)가 처리하게 한다.
     *       CallerRunsPolicy 는 요청 스레드에서 엑셀을 생성해버려 "즉시 응답" 요구를 깨므로 쓰지 않는다.</li>
     * </ul>
     */
    @Bean(name = EXPORT_EXECUTOR)
    public ThreadPoolTaskExecutor exportExecutor(ExportProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("export-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}

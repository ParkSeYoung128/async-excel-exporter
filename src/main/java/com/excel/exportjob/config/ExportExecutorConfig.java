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
     *   <li>종료 시: 실행 중인 작업은 끝날 때까지 기다린다(인터럽트하면 배포·재시작만으로 job 이 failed 가 된다).
     *       큐에 남아 아직 시작하지 않은 작업은 {@code ExportJobProcessor} 가 건너뛰어 pending 으로 남긴다.
     *       대기 시간을 넘기면 강제 종료되고, processing 에 남은 job 은 재시작 복구(#11)가 정리한다.</li>
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
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.shutdownAwaitSeconds());
        return executor;
    }
}

package com.excel.exportjob.maintenance;

import com.excel.exportjob.config.ExportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 정리 작업을 app.export.maintenance-interval 주기로 실행한다.
 * 주기를 설정 객체(Duration)에서 그대로 받기 위해 @Scheduled 대신 SchedulingConfigurer 로 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ExportMaintenanceScheduling implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ExportMaintenanceScheduling.class);

    private final ExportJobMaintenance maintenance;
    private final ExportProperties properties;

    public ExportMaintenanceScheduling(ExportJobMaintenance maintenance, ExportProperties properties) {
        this.maintenance = maintenance;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::runSafely, properties.maintenanceInterval());
    }

    /** 한 주기가 실패해도(DB 일시 장애 등) 다음 주기는 계속 실행되도록 예외를 로그로만 남긴다. */
    private void runSafely() {
        try {
            maintenance.runOnce();
        } catch (Exception e) {
            log.error("Export job maintenance failed", e);
        }
    }
}

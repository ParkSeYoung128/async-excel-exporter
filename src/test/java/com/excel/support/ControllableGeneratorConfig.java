package com.excel.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class ControllableGeneratorConfig {

    @Bean
    @Primary
    ControllableExportFileGenerator controllableExportFileGenerator() {
        return new ControllableExportFileGenerator();
    }
}

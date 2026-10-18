package com.queryforge.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SqlPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(SqlPlatformApplication.class, args);
    }
}

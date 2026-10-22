package com.zzj.admin;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * P13-M3A：独立管理服务。
 *
 * <p>只承载只读管理查询（用户分页、统计概览），数据一律经 Dubbo 从核心业务服务获取，
 * 本进程不连业务库、不复制用户表、不签发任何会话。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableDubbo
public class ZzjAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZzjAdminApplication.class, args);
    }
}

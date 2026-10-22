package com.queryforge.infrastructure.config;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import com.queryforge.domain.schedule.ScheduleProperties;

/**
 * 调度基础设施：启用 @Scheduled，配置单线程轮询器 + 有界虚拟线程执行器 + 可测试 Clock。
 * 轮询线程 poolSize=1 避免并发 claim 竞态；实际执行在独立 executor 中，不阻塞轮询线程。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(ScheduleProperties.class)
public class SchedulingConfig implements SchedulingConfigurer {

    private final ScheduleProperties properties;

    public SchedulingConfig(ScheduleProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setScheduler(java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "schedule-poller");
            thread.setDaemon(true);
            return thread;
        }));
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService scheduleExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

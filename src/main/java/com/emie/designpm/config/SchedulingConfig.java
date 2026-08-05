package com.emie.designpm.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulingConfig {
    /**
     * 定时任务统一使用有界线程池，避免同步、重试、监控任务在默认线程上互相阻塞，
     * 也避免异常情况下无限制并发占用数据库连接。
     */
    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("designpm-scheduler-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setErrorHandler(error ->
                org.slf4j.LoggerFactory.getLogger(SchedulingConfig.class)
                        .error("后台定时任务执行失败", error));
        return scheduler;
    }
}

package com.watchtracker.web.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Executor for background import jobs.
 *
 * <p>Deliberately a single-thread pool. {@code MongoEnrichmentWriter#saveAll} replaces the whole
 * collection ({@code deleteAll} then {@code saveAll}) rather than upserting — two imports running
 * concurrently could interleave those calls and corrupt the library. Serializing runs on one
 * thread sidesteps that; {@link ImportProperties} and the job queue (bounded, so a second submit
 * while one is running fails fast in {@code ImportJobStore} rather than queuing silently) are the
 * other half of that guard.
 */
@Configuration
public class AsyncConfig {
    @Bean(name = "importTaskExecutor")
    public Executor importTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("import-job-");
        executor.initialize();
        return executor;
    }
}

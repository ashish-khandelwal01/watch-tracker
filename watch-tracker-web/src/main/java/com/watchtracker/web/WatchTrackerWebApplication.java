package com.watchtracker.web;

import com.watchtracker.web.config.ImportProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the web module.
 *
 * <p>Depends only on {@code watch-tracker-core}. Import/enrichment is triggered from the web UI
 * ({@code /import}) using the same core services as the CLI; results go to MongoDB. The CLI is
 * not invoked and is not modified.
 */
@SpringBootApplication
@EnableConfigurationProperties(ImportProperties.class)
public class WatchTrackerWebApplication {
    public static void main(String[] args) {
        SpringApplication.run(WatchTrackerWebApplication.class, args);
    }
}

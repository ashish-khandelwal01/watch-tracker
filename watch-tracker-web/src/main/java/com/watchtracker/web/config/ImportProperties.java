package com.watchtracker.web.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Paths the web import flow uses for overrides, revoke list, and metadata cache.
 * CLI is untouched; these are web-only defaults (overridable via application.yml / env).
 */
@ConfigurationProperties(prefix = "watchtracker.import")
public class ImportProperties {
    /** Directory for TMDb/TVMaze response cache (same idea as CLI --cache). */
    private Path cacheDirectory = Path.of(".watch-tracker", "metadata-cache");
    /** Optional overrides.json for the web run. */
    private Path overridesFile;
    /** Optional revoked-overrides.json for the web run. */
    private Path revokedFile;

    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    public void setCacheDirectory(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public Path getOverridesFile() {
        return overridesFile;
    }

    public void setOverridesFile(Path overridesFile) {
        this.overridesFile = overridesFile;
    }

    public Path getRevokedFile() {
        return revokedFile;
    }

    public void setRevokedFile(Path revokedFile) {
        this.revokedFile = revokedFile;
    }
}

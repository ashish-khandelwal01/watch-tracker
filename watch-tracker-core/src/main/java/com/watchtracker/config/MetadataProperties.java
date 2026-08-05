package com.watchtracker.config;

import java.util.Locale;

/**
 * Metadata-provider configuration read from JVM system properties and environment variables.
 *
 * @param provider provider strategy selected by {@value #PROVIDER_PROPERTY}
 * @param tmdbApiReadAccessToken optional TMDb API Read Access Token
 */
public record MetadataProperties(Provider provider, String tmdbApiReadAccessToken) {
    /** JVM property that selects the metadata provider strategy. */
    public static final String PROVIDER_PROPERTY = "watchtracker.metadata.provider";

    /** Supported metadata lookup strategies. */
    public enum Provider {
        /** Use only TMDb; a TMDb token is required. */
        TMDB,
        /** Use only TVMaze; no API key is required. */
        TVMAZE,
        /** Try TMDb first and use TVMaze only for TMDb misses. */
        TMDB_WITH_TVMAZE_FALLBACK
    }

    /**
     * Creates configuration with the selected provider strategy and TMDb token.
     *
     * @param provider provider strategy
     * @param tmdbApiReadAccessToken TMDb API Read Access Token, when TMDb is selected
     */
    public MetadataProperties {
        provider = provider == null ? Provider.TMDB_WITH_TVMAZE_FALLBACK : provider;
        tmdbApiReadAccessToken = blankToNull(tmdbApiReadAccessToken);
    }

    /**
     * Loads configuration from the JVM property and the TMDb environment variable.
     *
     * @return resolved metadata configuration
     */
    public static MetadataProperties fromSystem() {
        String configuredProvider = System.getProperty(PROVIDER_PROPERTY, Provider.TMDB_WITH_TVMAZE_FALLBACK.name());
        try {
            return new MetadataProperties(
                    Provider.valueOf(configuredProvider.trim().toUpperCase(Locale.ROOT)),
                    System.getenv("TMDB_API_READ_ACCESS_TOKEN"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported " + PROVIDER_PROPERTY + ": " + configuredProvider
                            + ". Supported values: TMDB, TVMAZE, TMDB_WITH_TVMAZE_FALLBACK.",
                    exception);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

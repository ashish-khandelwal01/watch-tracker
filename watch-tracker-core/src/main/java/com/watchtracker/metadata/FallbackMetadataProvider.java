package com.watchtracker.metadata;

import java.util.Objects;
import java.util.Optional;

/** Delegates to a fallback provider only when the primary provider has no match. */
public final class FallbackMetadataProvider implements MetadataProvider {
    private final MetadataProvider primary;
    private final MetadataProvider fallback;

    /**
     * Creates a provider chain.
     *
     * @param primary provider queried first
     * @param fallback provider queried after a primary miss
     */
    public FallbackMetadataProvider(MetadataProvider primary, MetadataProvider fallback) {
        this.primary = Objects.requireNonNull(primary, "primary must not be null");
        this.fallback = Objects.requireNonNull(fallback, "fallback must not be null");
    }

    /**
     * Finds metadata using the primary provider before consulting its fallback.
     *
     * @param title canonical title to search
     * @return first match from the configured provider chain
     */
    @Override
    public Optional<ShowMetadata> findByTitle(String title) {
        Optional<ShowMetadata> primaryResult = primary.findByTitle(title);
        return primaryResult.isPresent() ? primaryResult : fallback.findByTitle(title);
    }

    /**
     * Resolves through the primary provider and consults the fallback only on a miss.
     *
     * @param showTitle canonical show title
     * @param season Netflix container number
     * @param episodeTitle title to resolve
     * @return a provider-resolved episode position
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
        Optional<WatchProgress> primaryResult = primary.resolveEpisode(showTitle, season, episodeTitle);
        return primaryResult.isPresent() ? primaryResult : fallback.resolveEpisode(showTitle, season, episodeTitle);
    }
}

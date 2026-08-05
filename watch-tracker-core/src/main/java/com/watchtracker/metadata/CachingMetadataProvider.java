package com.watchtracker.metadata;

import java.util.Objects;
import java.util.Optional;

/** Decorates a provider with a persistent cache of successful lookups. */
public final class CachingMetadataProvider implements MetadataProvider {
    private final MetadataProvider delegate;
    private final FileMetadataCache cache;

    /**
     * Creates a caching provider.
     *
     * @param delegate provider to call on cache misses
     * @param cache local metadata cache
     */
    public CachingMetadataProvider(MetadataProvider delegate, FileMetadataCache cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    /**
     * Finds metadata from the cache or its delegated provider.
     *
     * @param title canonical title to search
     * @return cached or freshly retrieved metadata
     */
    @Override
    public Optional<ShowMetadata> findByTitle(String title) {
        Optional<ShowMetadata> cached = cache.get(title);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ShowMetadata> found = delegate.findByTitle(title);
        found.ifPresent(metadata -> cache.put(title, metadata));
        return found;
    }

    /**
     * Delegates episode matching; providers cache their episode guides during an enrichment run.
     *
     * @param showTitle canonical show title
     * @param season Netflix container number
     * @param episodeTitle title to resolve
     * @return a provider-resolved episode position
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
        return delegate.resolveEpisode(showTitle, season, episodeTitle);
    }
}

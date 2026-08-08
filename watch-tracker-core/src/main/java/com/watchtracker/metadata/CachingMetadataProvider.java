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
     * Returns cached metadata for a title without calling the delegate provider.
     * This allows callers to consult the persistent cache for multiple candidate keys
     * before triggering any external lookup.
     *
     * @param title canonical title used as the cache key
     * @return cached metadata, if present
     */
    public Optional<ShowMetadata> getFromCache(String title) {
        return cache.get(title);
    }

    /**
     * Finds an episode position from the cache or its delegated provider. Providers also cache
     * their episode guides in memory during a single run, but this persists the resolved result so
     * a later run against the same history never repeats the provider lookup at all.
     *
     * @param showTitle canonical show title
     * @param season Netflix container number
     * @param episodeTitle title to resolve
     * @return a cached or freshly resolved episode position
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
        Optional<WatchProgress> cached = cache.getEpisode(showTitle, season, episodeTitle);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<WatchProgress> resolved = delegate.resolveEpisode(showTitle, season, episodeTitle);
        resolved.ifPresent(progress -> cache.putEpisode(showTitle, season, episodeTitle, progress));
        return resolved;
    }

    /**
     * Finds an episode position without a season constraint, using the persistent cache when
     * possible.
     *
     * @param showTitle canonical show title
     * @param episodeTitle title to resolve
     * @return a cached or freshly resolved episode position
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
        Optional<WatchProgress> cached = cache.getEpisodeAnySeason(showTitle, episodeTitle);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<WatchProgress> resolved = delegate.resolveEpisodeAnySeason(showTitle, episodeTitle);
        resolved.ifPresent(progress -> cache.putEpisodeAnySeason(showTitle, episodeTitle, progress));
        return resolved;
    }

    /**
     * Resolves against a known provider series id, caching successful results by id.
     *
     * @param providerId provider-specific series identifier
     * @param season Netflix container number
     * @param episodeTitle title to resolve
     * @return a cached or freshly resolved episode position
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(long providerId, int season, String episodeTitle) {
        Optional<WatchProgress> cached = cache.getEpisodeById(providerId, season, episodeTitle);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<WatchProgress> resolved = delegate.resolveEpisode(providerId, season, episodeTitle);
        resolved.ifPresent(progress -> cache.putEpisodeById(providerId, season, episodeTitle, progress));
        return resolved;
    }

    /**
     * Resolves a named episode anywhere in a series identified by provider id.
     *
     * @param providerId provider-specific series identifier
     * @param episodeTitle title to resolve
     * @return a cached or freshly resolved episode position
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(long providerId, String episodeTitle) {
        Optional<WatchProgress> cached = cache.getEpisodeAnySeasonById(providerId, episodeTitle);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<WatchProgress> resolved = delegate.resolveEpisodeAnySeason(providerId, episodeTitle);
        resolved.ifPresent(progress -> cache.putEpisodeAnySeasonById(providerId, episodeTitle, progress));
        return resolved;
    }
}

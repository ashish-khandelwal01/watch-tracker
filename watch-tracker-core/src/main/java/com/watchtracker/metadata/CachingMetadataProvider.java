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
            cached.ifPresent(this::cacheById);
            return cached;
        }
        Optional<ShowMetadata> found = delegate.findByTitle(title);
        found.ifPresent(metadata -> {
            cache.put(title, metadata);
            cacheById(metadata);
        });
        return found;
    }

    private void cacheById(ShowMetadata metadata) {
        if (metadata == null || metadata.providerId() <= 0 || metadata.provider() == null) {
            return;
        }
        String mediaType = metadata.type() == com.watchtracker.model.ShowType.MOVIE ? "movie" : "tv";
        cache.putById(metadata.provider(), mediaType, metadata.providerId(), metadata);
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

    /** Writes an id-keyed cache entry (e.g. after recovering an override from the title cache). */
    public void putById(String provider, String mediaType, long providerId, ShowMetadata metadata) {
        cache.putById(provider, mediaType, providerId, metadata);
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

    /**
     * Finds metadata by provider id using an id-keyed cache entry, then the network delegate.
     * Manual overrides call this path; caching by id is what makes overrides work offline after
     * a successful online resolve (title cache alone is not consulted here, so a wrong title-keyed
     * row cannot override a human-confirmed id).
     *
     * @param provider which provider's id space this is
     * @param mediaType {@code "movie"} or {@code "tv"}, meaningful only to TMDb
     * @param providerId the provider-specific identifier
     * @return cached or freshly retrieved metadata
     */
    @Override
    public Optional<ShowMetadata> findById(String provider, String mediaType, long providerId) {
        Optional<ShowMetadata> cached = cache.getById(provider, mediaType, providerId);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<ShowMetadata> found = delegate.findById(provider, mediaType, providerId);
        found.ifPresent(metadata -> {
            cache.putById(provider, mediaType, providerId, metadata);
            if (metadata.title() != null && !metadata.title().isBlank()) {
                cache.put(metadata.title(), metadata);
            }
        });
        return found;
    }

    /**
     * Forwards title → series-id seeding to the delegate provider.
     *
     * @param title title key to associate
     * @param providerId provider-specific series identifier
     */
    @Override
    public void rememberSeriesMapping(String title, long providerId) {
        delegate.rememberSeriesMapping(title, providerId);
    }
}

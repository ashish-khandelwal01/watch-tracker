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
     * <p>A primary timeout or other transient failure (after the provider's own retries) is treated
     * as a miss so the fallback chain can still succeed — the import must not abort when TMDb is
     * unreachable if TVMaze can answer.
     *
     * @param title canonical title to search
     * @return first match from the configured provider chain
     */
    @Override
    public Optional<ShowMetadata> findByTitle(String title) {
        Optional<ShowMetadata> primaryResult = tryPrimary(() -> primary.findByTitle(title));
        return primaryResult.isPresent() ? primaryResult : fallback.findByTitle(title);
    }

    /**
     * Resolves an episode through a four-tier cascade: the primary provider's own season, the
     * fallback provider's own season, then — only once both agree the episode is not in that
     * season — the primary provider searched without a season restriction, then the fallback
     * provider searched the same way.
     *
     * <p>The season-agnostic tiers exist for providers whose season boundaries disagree with the
     * source platform's numbering (for example, a platform's five numbered containers merged into
     * three provider seasons), which otherwise leaves an episode that legitimately exists
     * permanently unresolved. They are tried last because they are more expensive and, in the rare
     * case of a duplicated episode title across seasons, less precise than a season-scoped match.
     *
     * <p>Primary failures (timeouts after retries, etc.) are treated as misses so fallback tiers
     * still run.
     *
     * @param showTitle canonical show title
     * @param season Netflix container number
     * @param episodeTitle title to resolve
     * @return the first match found across the four-tier cascade
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
        Optional<WatchProgress> primaryResult =
                tryPrimary(() -> primary.resolveEpisode(showTitle, season, episodeTitle));
        if (primaryResult.isPresent()) {
            return primaryResult;
        }
        Optional<WatchProgress> fallbackResult = fallback.resolveEpisode(showTitle, season, episodeTitle);
        if (fallbackResult.isPresent()) {
            return fallbackResult;
        }
        Optional<WatchProgress> primaryAnySeason =
                tryPrimary(() -> primary.resolveEpisodeAnySeason(showTitle, episodeTitle));
        return primaryAnySeason.isPresent()
                ? primaryAnySeason
                : fallback.resolveEpisodeAnySeason(showTitle, episodeTitle);
    }

    /**
     * Resolves a named episode without a season constraint, trying primary then fallback.
     * Primary failures are treated as misses so the fallback is still consulted.
     *
     * @param showTitle canonical show title
     * @param episodeTitle title to resolve
     * @return first match from the configured provider chain
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
        Optional<WatchProgress> primaryResult =
                tryPrimary(() -> primary.resolveEpisodeAnySeason(showTitle, episodeTitle));
        return primaryResult.isPresent()
                ? primaryResult
                : fallback.resolveEpisodeAnySeason(showTitle, episodeTitle);
    }

    /**
     * Resolves against a known provider series id. Primary is tried first; a miss (including a
     * primary timeout/failure) falls through to the fallback. Provider id namespaces differ (TMDb
     * versus TVMaze), so a miss on the wrong provider is expected and harmless.
     *
     * @param providerId provider-specific series identifier
     * @param season Netflix container number
     * @param episodeTitle title to resolve
     * @return first match from the configured provider chain
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(long providerId, int season, String episodeTitle) {
        // Id namespaces differ across providers. Try primary then fallback scoped only; the caller
        // invokes resolveEpisodeAnySeason explicitly when a season-agnostic search is wanted.
        Optional<WatchProgress> primaryResult =
                tryPrimary(() -> primary.resolveEpisode(providerId, season, episodeTitle));
        if (primaryResult.isPresent()) {
            return primaryResult;
        }
        return fallback.resolveEpisode(providerId, season, episodeTitle);
    }

    /**
     * Resolves a named episode anywhere in a series identified by provider id.
     * Primary failures are treated as misses so the fallback is still consulted.
     *
     * @param providerId provider-specific series identifier
     * @param episodeTitle title to resolve
     * @return first match from the configured provider chain
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(long providerId, String episodeTitle) {
        Optional<WatchProgress> primaryResult =
                tryPrimary(() -> primary.resolveEpisodeAnySeason(providerId, episodeTitle));
        return primaryResult.isPresent()
                ? primaryResult
                : fallback.resolveEpisodeAnySeason(providerId, episodeTitle);
    }

    /**
     * Invokes a primary-provider call. Any runtime failure (timeout after retries, HTTP error,
     * etc.) is treated as an empty result so the fallback provider can still be used. The import
     * must not abort when only the primary is unreachable.
     */
    private static <T> Optional<T> tryPrimary(java.util.function.Supplier<Optional<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Seeds the primary provider only. Provider id namespaces differ (TMDb versus TVMaze);
     * the matching provider already seeds itself inside findByTitle. This call exists so stripped
     * Netflix titles (e.g. without "Limited Series") still resolve against the primary series id.
     *
     * @param title title key to associate
     * @param providerId provider-specific series identifier
     */
    @Override
    public void rememberSeriesMapping(String title, long providerId) {
        primary.rememberSeriesMapping(title, providerId);
    }
}

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
     * @param showTitle canonical show title
     * @param season Netflix container number
     * @param episodeTitle title to resolve
     * @return the first match found across the four-tier cascade
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
        Optional<WatchProgress> primaryResult = primary.resolveEpisode(showTitle, season, episodeTitle);
        if (primaryResult.isPresent()) {
            return primaryResult;
        }
        Optional<WatchProgress> fallbackResult = fallback.resolveEpisode(showTitle, season, episodeTitle);
        if (fallbackResult.isPresent()) {
            return fallbackResult;
        }
        Optional<WatchProgress> primaryAnySeason = primary.resolveEpisodeAnySeason(showTitle, episodeTitle);
        return primaryAnySeason.isPresent()
                ? primaryAnySeason
                : fallback.resolveEpisodeAnySeason(showTitle, episodeTitle);
    }

    /**
     * Resolves a named episode without a season constraint, trying primary then fallback.
     *
     * @param showTitle canonical show title
     * @param episodeTitle title to resolve
     * @return first match from the configured provider chain
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
        Optional<WatchProgress> primaryResult = primary.resolveEpisodeAnySeason(showTitle, episodeTitle);
        return primaryResult.isPresent()
                ? primaryResult
                : fallback.resolveEpisodeAnySeason(showTitle, episodeTitle);
    }

    /**
     * Resolves against a known provider series id. Primary is tried first; a miss falls through to
     * the fallback. Provider id namespaces differ (TMDb versus TVMaze), so a miss on the wrong
     * provider is expected and harmless.
     *
     * @param providerId provider-specific series identifier
     * @param season Netflix container number
     * @param episodeTitle title to resolve
     * @return first match from the configured provider chain
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(long providerId, int season, String episodeTitle) {
        Optional<WatchProgress> primaryResult = primary.resolveEpisode(providerId, season, episodeTitle);
        if (primaryResult.isPresent()) {
            return primaryResult;
        }
        Optional<WatchProgress> fallbackResult = fallback.resolveEpisode(providerId, season, episodeTitle);
        if (fallbackResult.isPresent()) {
            return fallbackResult;
        }
        Optional<WatchProgress> primaryAnySeason = primary.resolveEpisodeAnySeason(providerId, episodeTitle);
        return primaryAnySeason.isPresent()
                ? primaryAnySeason
                : fallback.resolveEpisodeAnySeason(providerId, episodeTitle);
    }

    /**
     * Resolves a named episode anywhere in a series identified by provider id.
     *
     * @param providerId provider-specific series identifier
     * @param episodeTitle title to resolve
     * @return first match from the configured provider chain
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(long providerId, String episodeTitle) {
        Optional<WatchProgress> primaryResult = primary.resolveEpisodeAnySeason(providerId, episodeTitle);
        return primaryResult.isPresent()
                ? primaryResult
                : fallback.resolveEpisodeAnySeason(providerId, episodeTitle);
    }
}

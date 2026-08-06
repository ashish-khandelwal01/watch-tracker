package com.watchtracker.metadata;

import com.watchtracker.config.MetadataProperties;
import java.util.Optional;

/** Looks up trusted, optional metadata for a canonical title. */
public interface MetadataProvider {
    /**
     * Finds metadata for a canonical show or movie title.
     *
     * @param title canonical title to search
     * @return provider metadata when a reliable match is found
     */
    Optional<ShowMetadata> findByTitle(String title);

    /**
     * Resolves a provider episode number from a Netflix episode title.
     *
     * @param showTitle canonical show title
     * @param season Netflix container number to match against a provider season
     * @param episodeTitle Netflix episode title
     * @return resolved provider position when the episode title is known
     */
    default Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
        return Optional.empty();
    }

    /**
     * Resolves a named episode without restricting the search to one season.
     *
     * <p>A provider's own season boundaries can disagree with the source platform's numbering —
     * for example, a platform's five numbered containers merged into three provider seasons —
     * which otherwise leaves an episode that legitimately exists permanently unresolved. This is a
     * more expensive, best-effort search and is only attempted after season-scoped resolution has
     * failed on every configured provider.
     *
     * @param showTitle canonical show title
     * @param episodeTitle title to resolve
     * @return resolved provider position when the episode title is found anywhere in the show
     */
    default Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
        return Optional.empty();
    }

    /**
     * Creates the provider selected by metadata configuration.
     *
     * @param properties provider configuration
     * @return selected provider, optionally composed with a TVMaze fallback
     */
    static MetadataProvider from(MetadataProperties properties) {
        return switch (properties.provider()) {
            case TVMAZE -> new TvMazeMetadataProvider();
            case TMDB -> new TmdbMetadataProvider(requiredTmdbToken(properties));
            case TMDB_WITH_TVMAZE_FALLBACK -> tmdbWithTvMazeFallback(properties);
        };
    }

    /** Creates a fallback chain, or uses TVMaze alone when no TMDb token is configured. */
    private static MetadataProvider tmdbWithTvMazeFallback(MetadataProperties properties) {
        MetadataProvider tvMaze = new TvMazeMetadataProvider();
        return properties.tmdbApiReadAccessToken() == null
                ? tvMaze
                : new FallbackMetadataProvider(new TmdbMetadataProvider(properties.tmdbApiReadAccessToken()), tvMaze);
    }

    /** Obtains the required token for the TMDb-only selection. */
    private static String requiredTmdbToken(MetadataProperties properties) {
        if (properties.tmdbApiReadAccessToken() == null) {
            throw new IllegalArgumentException("TMDb requires the TMDB_API_READ_ACCESS_TOKEN environment variable");
        }
        return properties.tmdbApiReadAccessToken();
    }
}

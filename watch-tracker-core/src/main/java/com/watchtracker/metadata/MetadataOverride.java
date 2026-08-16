package com.watchtracker.metadata;

/**
 * A human-confirmed correction pointing a show at a specific provider entity, bypassing title
 * search entirely.
 *
 * <p>Optional {@code progressSeason} / {@code progressEpisode} pin the highest provider position
 * when automatic episode-title resolution cannot map Netflix titles. Completion status is
 * <b>not</b> set here — it is always derived from {@link ShowMetadata#totalEpisodes()} (and
 * production-ended) the same way automatic progress is. Progress pins are ignored when metadata
 * is missing: there is no total to compare against and no trusted type.
 *
 * @param title the key used to find matching shows
 * @param provider {@code "TMDB"} or {@code "TVMAZE"}
 * @param mediaType {@code "movie"} or {@code "tv"} ({@code "series"} → {@code "tv"})
 * @param providerId the provider-specific identifier
 * @param progressSeason highest provider season (series only; both season and episode required)
 * @param progressEpisode highest provider episode (series only)
 */
public record MetadataOverride(
        String title,
        String provider,
        String mediaType,
        long providerId,
        Integer progressSeason,
        Integer progressEpisode) {

    /** Metadata-only override (no progress pin). */
    public MetadataOverride(String title, String provider, String mediaType, long providerId) {
        this(title, provider, mediaType, providerId, null, null);
    }

    /** True when both season and episode are set for a series progress pin. */
    public boolean hasProgressPin() {
        return progressSeason != null && progressEpisode != null;
    }
}

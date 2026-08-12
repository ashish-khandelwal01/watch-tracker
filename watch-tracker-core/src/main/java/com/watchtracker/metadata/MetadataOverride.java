package com.watchtracker.metadata;

/**
 * A human-confirmed correction pointing a show at a specific provider entity, bypassing title
 * search entirely.
 *
 * @param title the key used to find matching shows — typically the canonical title or a raw alias
 *     copied from {@link ReviewItem}. Matching is exact on title/alias, or a colon-prefix on the
 *     canonical title (see {@link MetadataOverrideStore})
 * @param provider which provider this id belongs to: {@code "TMDB"} or {@code "TVMAZE"}
 * @param mediaType {@code "movie"} or {@code "tv"} (case-insensitive); {@code "series"} is accepted
 *     as a synonym for {@code "tv"}. Meaningful only for TMDB (its id space is split by media
 *     type), ignored for TVMAZE. Unrecognized values cause the override to be skipped at load time.
 * @param providerId the provider-specific identifier for the correct entity
 */
public record MetadataOverride(String title, String provider, String mediaType, long providerId) {}

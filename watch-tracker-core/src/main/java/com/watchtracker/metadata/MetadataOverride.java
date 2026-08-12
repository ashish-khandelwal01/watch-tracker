package com.watchtracker.metadata;

/**
 * A human-confirmed correction pointing a show at a specific provider entity, bypassing title
 * search entirely.
 *
 * @param title the show's canonical (post-normalization) title this override applies to — must
 *     match {@link com.watchtracker.model.Show#title()} exactly (see {@link MetadataOverrideStore}
 *     for the consequence of a title that no longer matches)
 * @param provider which provider this id belongs to: {@code "TMDB"} or {@code "TVMAZE"}
 * @param mediaType {@code "movie"} or {@code "tv"}; meaningful only for TMDB (its id space is
 *     split by media type), ignored for TVMAZE
 * @param providerId the provider-specific identifier for the correct entity
 */
public record MetadataOverride(String title, String provider, String mediaType, long providerId) {}

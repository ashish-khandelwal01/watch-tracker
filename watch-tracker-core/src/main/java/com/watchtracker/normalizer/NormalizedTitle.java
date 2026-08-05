package com.watchtracker.normalizer;

/**
 * Structured title metadata extracted from a raw streaming-platform title.
 *
 * @param canonicalShow canonical title used for grouping
 * @param season extracted season, part, book, chapter, or volume number
 * @param episode extracted episode number
 * @param episodeTitle extracted unnumbered or trailing episode title
 */
public record NormalizedTitle(String canonicalShow, Integer season, Integer episode, String episodeTitle) {}

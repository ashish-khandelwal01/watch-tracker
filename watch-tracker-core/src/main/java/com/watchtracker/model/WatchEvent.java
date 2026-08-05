package com.watchtracker.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * An immutable record of one title watched on a streaming platform.
 *
 * @param id stable event identifier
 * @param rawTitle unmodified title supplied by the platform
 * @param watchedAt date and time at which the title was watched
 * @param platform source platform name
 * @param canonicalShow normalized title used for grouping
 * @param season normalized season number
 * @param episode normalized episode number
 * @param episodeTitle normalized episode title
 * @param parsed whether normalization has completed
 */
public record WatchEvent(
        UUID id,
        String rawTitle,
        LocalDateTime watchedAt,
        String platform,
        String canonicalShow,
        Integer season,
        Integer episode,
        String episodeTitle,
        boolean parsed) {
    /** Creates a watch event and validates its required source fields. */
    public WatchEvent {
        Objects.requireNonNull(id, "id must not be null");
        rawTitle = requireText(rawTitle, "rawTitle");
        Objects.requireNonNull(watchedAt, "watchedAt must not be null");
        platform = requireText(platform, "platform");
    }

    /**
     * Returns a source event that has not yet been normalized.
     *
     * @param rawTitle platform-supplied title
     * @param watchedAt viewing timestamp
     * @param platform source platform name
     * @return a raw watch event
     */
    public static WatchEvent raw(String rawTitle, LocalDateTime watchedAt, String platform) {
        return new WatchEvent(UUID.randomUUID(), rawTitle, watchedAt, platform, null, null, null, null, false);
    }

    /**
     * Returns this event with title metadata supplied by a normalizer.
     *
     * @param show canonical show title
     * @param normalizedSeason normalized season number
     * @param normalizedEpisode normalized episode number
     * @param title normalized episode title
     * @return a normalized copy of this event
     */
    public WatchEvent normalized(String show, Integer normalizedSeason, Integer normalizedEpisode, String title) {
        return new WatchEvent(
                id, rawTitle, watchedAt, platform, show, normalizedSeason, normalizedEpisode, title, true);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

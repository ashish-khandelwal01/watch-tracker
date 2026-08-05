package com.watchtracker.model;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * An immutable aggregate of watch events belonging to one canonical show.
 *
 * @param id stable aggregate identifier
 * @param title canonical title
 * @param aliases raw platform titles observed for this show
 * @param type trusted classification, if available
 * @param firstWatched earliest imported watch event
 * @param lastWatched latest imported watch event
 * @param highestSeasonSeen highest normalized season observed
 * @param highestEpisodeSeen highest normalized episode observed
 * @param watchCount total imported watch-event count
 */
public record Show(
        UUID id,
        String title,
        Set<String> aliases,
        ShowType type,
        LocalDateTime firstWatched,
        LocalDateTime lastWatched,
        Integer highestSeasonSeen,
        Integer highestEpisodeSeen,
        int watchCount) {
    /** Creates a show aggregate. */
    public Show {
        Objects.requireNonNull(id, "id must not be null");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        aliases = Set.copyOf(aliases == null ? Set.of() : aliases);
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(firstWatched, "firstWatched must not be null");
        Objects.requireNonNull(lastWatched, "lastWatched must not be null");
        if (watchCount < 1) {
            throw new IllegalArgumentException("watchCount must be positive");
        }
    }
}

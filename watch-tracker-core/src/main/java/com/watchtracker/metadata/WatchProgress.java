package com.watchtracker.metadata;

import com.watchtracker.model.ShowType;
import com.watchtracker.model.WatchStatus;
import java.util.Objects;

/**
 * A provider-resolved episode position from imported watch events.
 *
 * @param type provider-resolved show type (MOVIE, SERIES, etc.)
 * @param season canonical provider season number
 * @param episode canonical provider episode number
 * @param provider provider that resolved the episode title
 * @param status user-declared or inferred watch status
 */
public record WatchProgress(ShowType type, Integer season, Integer episode, String provider, WatchStatus status)
        implements Comparable<WatchProgress> {
    /**
     * Creates a resolved progress position.
     *
     * <p>For movies the season and episode must be absent and the status defaults to COMPLETED.
     * For non-movie shows season and episode must be present and the status defaults to UNKNOWN.
     */
    public WatchProgress {
        // Allow type to be null or UNKNOWN: callers may omit a resolved type; do not hard-fail.
        if (type == null || type == ShowType.UNKNOWN) {
            // When type is unknown, accept whatever season/episode values are supplied but default
            // the status to UNKNOWN when not explicitly provided.
            if (status == null) status = WatchStatus.UNKNOWN;
        } else if (type == ShowType.MOVIE) {
            if (season != null || episode != null) {
                throw new IllegalArgumentException("movies must not have season or episode");
            }
            if (status == null) status = WatchStatus.COMPLETED;
        } else {
            if (season == null || episode == null) {
                throw new IllegalArgumentException("season and episode must be present for non-movie shows");
            }
            if (season < 1) {
                throw new IllegalArgumentException("season must be positive");
            }
            if (episode < 1) {
                throw new IllegalArgumentException("episode must be positive");
            }
            if (status == null) status = WatchStatus.UNKNOWN;
        }

        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        provider = provider.trim();
    }

    /** Backwards-compatible convenience constructor assuming SERIES when type omitted. */
    public WatchProgress(Integer season, Integer episode, String provider) {
        this(ShowType.SERIES, season, episode, provider, null);
    }

    /** Convenience constructor without explicit status. */
    public WatchProgress(ShowType type, Integer season, Integer episode, String provider) {
        this(type, season, episode, provider, null);
    }

    /** Compares positions by season and then episode number. Nulls are treated as 0. */
    @Override
    public int compareTo(WatchProgress other) {
        if (other == null) {
            throw new NullPointerException("other must not be null");
        }
        int s1 = season == null ? 0 : season;
        int s2 = other.season == null ? 0 : other.season;
        int seasonComparison = Integer.compare(s1, s2);
        if (seasonComparison == 0) {
            int e1 = episode == null ? 0 : episode;
            int e2 = other.episode == null ? 0 : other.episode;
            int episodeComparison = Integer.compare(e1, e2);
            if (episodeComparison == 0) {
                int typeComp = compareObjects(type, other.type);
                if (typeComp != 0) return typeComp;
                int providerComp = compareObjects(provider, other.provider);
                if (providerComp != 0) return providerComp;
                return compareObjects(status, other.status);
            }
            return episodeComparison;
        }
        return seasonComparison;
    }

    private static <T extends Comparable<T>> int compareObjects(T c1, T c2) {
        if (c1 == null && c2 == null) return 0;
        if (c1 == null) return -1;
        if (c2 == null) return 1;
        return c1.compareTo(c2);
    }
}

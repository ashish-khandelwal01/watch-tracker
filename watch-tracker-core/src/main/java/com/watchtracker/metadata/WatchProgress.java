package com.watchtracker.metadata;

/**
 * A provider-resolved episode position from imported watch events.
 *
 * @param season canonical provider season number
 * @param episode canonical provider episode number
 * @param provider provider that resolved the episode title
 */
public record WatchProgress(int season, int episode, String provider) implements Comparable<WatchProgress> {
    /**
     * Creates a resolved progress position.
     *
     * @throws IllegalArgumentException if season or episode is not positive
     */
    public WatchProgress {
        if (season < 1 || episode < 1) {
            throw new IllegalArgumentException("season and episode must be positive");
        }
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        provider = provider.trim();
    }

    /** Compares positions by season and then episode number. */
    @Override
    public int compareTo(WatchProgress other) {
        int seasonComparison = Integer.compare(season, other.season);
        return seasonComparison == 0 ? Integer.compare(episode, other.episode) : seasonComparison;
    }
}

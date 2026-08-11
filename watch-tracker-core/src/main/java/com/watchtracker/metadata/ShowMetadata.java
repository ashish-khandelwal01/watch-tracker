package com.watchtracker.metadata;

import com.watchtracker.model.ShowType;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provider-sourced metadata that is kept distinct from imported watch history.
 *
 * @param provider source provider name
 * @param providerId provider-specific stable identifier
 * @param title provider's canonical title
 * @param type provider-sourced show type
 * @param genres provider-sourced genre names
 * @param overview provider-sourced synopsis
 * @param posterPath provider-sourced poster path
 * @param releaseDate first release or air date
 * @param totalEpisodes total episode count across all seasons, when the provider reports one;
 *     {@code null} when unknown or not applicable (e.g. movies). Used to mechanically derive
 *     {@link com.watchtracker.model.CompletionStatus} by comparing against how many distinct
 *     episodes have been watched.
 * @param productionEnded whether the provider reports the series has finished its run (as opposed
 *     to still releasing new episodes or seasons); {@code null} when unknown or not applicable.
 *     Matters because a viewer fully caught up on a still-airing show has not "completed" it —
 *     more of it is still to come.
 * @param matchConfidence how confidently this result was matched to the queried title (see
 *     {@link MatchConfidence}); {@code null} when unknown, e.g. for metadata not produced via a
 *     provider search (test fixtures, previously-cached snapshots without this field, etc.).
 */
public record ShowMetadata(
        String provider,
        long providerId,
        String title,
        ShowType type,
        List<String> genres,
        String overview,
        String posterPath,
        LocalDate releaseDate,
        Integer totalEpisodes,
        Boolean productionEnded,
        MatchConfidence matchConfidence) {
    /** Creates immutable metadata from a trusted external provider. */
    public ShowMetadata {
        provider = requireText(provider, "provider");
        if (providerId <= 0) {
            throw new IllegalArgumentException("providerId must be positive");
        }
        title = requireText(title, "title");
        Objects.requireNonNull(type, "type must not be null");
        genres = List.copyOf(genres == null ? List.of() : genres);
        if (totalEpisodes != null && totalEpisodes < 0) {
            throw new IllegalArgumentException("totalEpisodes must not be negative");
        }
    }

    /**
     * Backwards-compatible convenience constructor for callers without episode-count data (e.g.
     * movies, or existing test fixtures). {@code totalEpisodes} and {@code productionEnded} default
     * to {@code null} (unknown), and {@code matchConfidence} defaults to {@code null} (unknown).
     */
    public ShowMetadata(
            String provider,
            long providerId,
            String title,
            ShowType type,
            List<String> genres,
            String overview,
            String posterPath,
            LocalDate releaseDate) {
        this(provider, providerId, title, type, genres, overview, posterPath, releaseDate, null, null, null);
    }

    /**
     * Backwards-compatible convenience constructor for callers that have episode-count data but not
     * a match confidence (e.g. existing test fixtures predating {@link MatchConfidence}).
     * {@code matchConfidence} defaults to {@code null} (unknown).
     */
    public ShowMetadata(
            String provider,
            long providerId,
            String title,
            ShowType type,
            List<String> genres,
            String overview,
            String posterPath,
            LocalDate releaseDate,
            Integer totalEpisodes,
            Boolean productionEnded) {
        this(
                provider,
                providerId,
                title,
                type,
                genres,
                overview,
                posterPath,
                releaseDate,
                totalEpisodes,
                productionEnded,
                null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
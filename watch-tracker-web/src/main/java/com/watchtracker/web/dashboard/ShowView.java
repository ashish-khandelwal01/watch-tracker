package com.watchtracker.web.dashboard;

import java.util.List;

/**
 * Read model for library list and detail pages — built from an {@code EnrichedShow} payload in
 * Mongo so templates never parse JSON themselves.
 */
public record ShowView(
        String id,
        String title,
        List<String> aliases,
        String matchConfidence,
        String showType,
        String posterUrlSmall,
        String posterUrlLarge,
        String overview,
        List<String> genres,
        String releaseDate,
        String provider,
        Long providerId,
        String progressSummary,
        Integer progressSeason,
        Integer progressEpisode,
        String completionStatus,
        int watchCount,
        String firstWatched,
        String lastWatched,
        Integer highestSeasonSeen,
        Integer highestEpisodeSeen) {

    public boolean needsReview() {
        return matchConfidence == null
                || (!"EXACT".equals(matchConfidence) && !"MANUAL_OVERRIDE".equals(matchConfidence));
    }

    public boolean hasPoster() {
        return posterUrlSmall != null && !posterUrlSmall.isBlank();
    }
}

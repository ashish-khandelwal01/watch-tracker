package com.watchtracker.web.dashboard;

import java.util.List;

/** Read model for library list and detail pages. */
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
        Integer highestEpisodeSeen,
        List<String> reviewReasons) {

    public boolean needsReview() {
        return reviewReasons != null && !reviewReasons.isEmpty();
    }

    public boolean hasPoster() {
        return posterUrlSmall != null && !posterUrlSmall.isBlank();
    }

    public String reviewReasonsLabel() {
        if (reviewReasons == null || reviewReasons.isEmpty()) {
            return "";
        }
        return String.join(", ", reviewReasons);
    }
}

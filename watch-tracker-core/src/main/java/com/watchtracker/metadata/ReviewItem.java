package com.watchtracker.metadata;

import java.util.List;
import java.util.UUID;

/**
 * A single show flagged for manual review, with enough context for a human (eventually via the
 * planned web UI) to confirm the current match or supply a corrected provider id.
 *
 * @param showId the show's stable id
 * @param title the show's canonical (post-normalization) title
 * @param aliases the show's raw, pre-normalization watched titles
 * @param watchCount how many times this show was watched — a rough signal of how much a wrong
 *     match would matter (a 40-episode show resolving wrong is more consequential than a
 *     one-off movie)
 * @param reasons why this show was flagged; a show can have more than one
 * @param currentMatch the metadata currently on file for this show, if any, so a reviewer can see
 *     what was picked before deciding whether to override it
 */
public record ReviewItem(
        UUID showId,
        String title,
        List<String> aliases,
        int watchCount,
        List<ReviewReason> reasons,
        CurrentMatch currentMatch) {

    /**
     * A trimmed view of the currently-matched metadata — just enough for a reviewer to recognize
     * what was picked, not the full {@link ShowMetadata} (poster paths, overviews, etc. aren't
     * useful for this decision and would bloat the export).
     */
    public record CurrentMatch(String provider, long providerId, String title, MatchConfidence confidence) {}
}

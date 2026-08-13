package com.watchtracker.metadata;

/**
 * Why a show was flagged for manual review, roughly in order of how urgently it needs a human to
 * look at it. A show can have more than one reason (e.g. a show with no metadata also has no
 * progress).
 */
public enum ReviewReason {
    /** No provider metadata was found for this show at all. */
    NO_METADATA_MATCH,
    /**
     * Metadata was found, but not via an exact title match (see {@link MatchConfidence}) — the
     * weaker tiers are where past real mismatches (e.g. "House, M.D." resolving to an unrelated
     * documentary short) have come from, so these are worth a second look even though most turn out
      * fine. Also applied when metadata type is {@code MOVIE} and the show has more than one raw
     * alias, which often means distinct films were incorrectly grouped under one title.
     */
    LOW_CONFIDENCE_MATCH,
    /** Metadata was found, but no watched-episode progress could be resolved from it. */
    NO_PROGRESS_RESOLVED
}

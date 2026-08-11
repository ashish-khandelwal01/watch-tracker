package com.watchtracker.metadata;

/**
 * How confidently a provider's search result was matched to a queried title, from strongest to
 * weakest. This is orthogonal to whether the match is actually correct — an {@link #EXACT} match
 * can still be the wrong entity if the provider's catalog has an unrelated same-titled entry (see
 * {@code TmdbMetadataProvider.selectBestMatch}'s notes on why a recency tiebreak was tried and
 * reverted) — but it's a useful, cheap signal for surfacing which matches are most worth a human
 * double-checking, since the weaker tiers are exactly where past real mismatches (e.g. "House,
 * M.D." resolving to an unrelated documentary short) have come from.
 */
public enum MatchConfidence {
    /** The candidate's own title/name matched the query exactly (case/punctuation-insensitive). */
    EXACT,
    /**
     * No exact match existed; the candidate was chosen because its title/name and the query are
     * textually related (one contains the other) and it was the closest-length such candidate.
     */
    CLOSEST_RELATED,
    /**
     * No exact or textually-related candidate existed; the first valid result was used as a last
     * resort. This is the weakest tier and the one most likely to be wrong.
     */
    FIRST_RESULT_FALLBACK
}

package com.watchtracker.metadata;

import com.watchtracker.model.ShowType;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the list of shows worth a human's attention from a completed enrichment run, so the
 * pipeline surfaces its own uncertain matches instead of requiring an offline script to re-derive
 * them from the exported JSON afterward. Only flagged shows are included — this is not a dump of
 * every show, just the ones a {@link ReviewReason} applies to.
 *
 * <p>This is a pure post-processing pass over the already-computed {@link EnrichedShow} list: it
 * doesn't re-query providers or change any matching behavior, so it carries no risk to the actual
 * enrichment results.
 */
public final class ReviewQueueBuilder {
    private ReviewQueueBuilder() {}

    /**
     * Derives the review queue from a completed enrichment run.
     *
     * @param enrichedShows the result of {@link MetadataEnrichmentService#enrich}
     * @return flagged shows only, in the same order as {@code enrichedShows}
     */
    public static List<ReviewItem> build(List<EnrichedShow> enrichedShows) {
        List<ReviewItem> items = new ArrayList<>();
        for (EnrichedShow enriched : enrichedShows) {
            List<ReviewReason> reasons = new ArrayList<>();
            ReviewItem.CurrentMatch currentMatch = null;
            if (enriched.metadata().isEmpty()) {
                reasons.add(ReviewReason.NO_METADATA_MATCH);
            } else {
                ShowMetadata metadata = enriched.metadata().get();
                boolean isTrustedMatch = metadata.matchConfidence() == MatchConfidence.EXACT
                        || metadata.matchConfidence() == MatchConfidence.MANUAL_OVERRIDE;
                if (metadata.matchConfidence() != null && !isTrustedMatch) {
                    reasons.add(ReviewReason.LOW_CONFIDENCE_MATCH);
                }
                // A movie with more than one raw alias often means distinct films were grouped under
                // one franchise title (e.g. two Kingsman movies). Flag for review even when the
                // provider match itself was exact or manually overridden.
                if (metadata.type() == ShowType.MOVIE && enriched.show().aliases().size() > 1) {
                    if (!reasons.contains(ReviewReason.LOW_CONFIDENCE_MATCH)) {
                        reasons.add(ReviewReason.LOW_CONFIDENCE_MATCH);
                    }
                }
                if (enriched.progress().isEmpty()) {
                    reasons.add(ReviewReason.NO_PROGRESS_RESOLVED);
                }
                currentMatch = new ReviewItem.CurrentMatch(
                        metadata.provider(), metadata.providerId(), metadata.title(), metadata.matchConfidence());
            }
            if (reasons.isEmpty()) {
                continue;
            }
            items.add(new ReviewItem(
                    enriched.show().id(),
                    enriched.show().title(),
                    List.copyOf(enriched.show().aliases()),
                    enriched.show().watchCount(),
                    List.copyOf(reasons),
                    currentMatch));
        }
        return items;
    }
}

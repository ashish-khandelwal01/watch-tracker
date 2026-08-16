package com.watchtracker.web.persistence;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Spring Data repository over {@link EnrichedShowDocument}.
 *
 * <p>Public (rather than package-private like {@link EnrichedShowMongoMapper}) because the
 * dashboard reads documents directly — it only needs the indexed projection fields (title,
 * aliases, matchConfidence), not the full deserialized {@code EnrichedShow}, so it has no need to
 * go through {@link MongoPreviousEnrichmentSnapshot} or the mapper.
 */
public interface EnrichedShowMongoRepository extends MongoRepository<EnrichedShowDocument, String> {
    /**
     * Shows whose current match, if any, is not {@code MANUAL_OVERRIDE} or {@code EXACT} — a
     * Mongo-side approximation of "needs another look" for the dashboard listing. This is
     * deliberately cruder than {@code ReviewQueueBuilder} (no progress-missing or no-metadata
     * reasons, no per-show explanation) since it only drives a read-only overview list; the
     * authoritative review queue is still {@code review-queue.json} produced by an enrichment run.
     *
     * @param confidences confidence values considered exempt from review (typically {@code
     *     MANUAL_OVERRIDE} and {@code EXACT})
     * @return documents whose {@code matchConfidence} is null or outside {@code confidences}
     */
    List<EnrichedShowDocument> findByMatchConfidenceNotInOrMatchConfidenceIsNull(List<String> confidences);

    /**
     * All documents ordered by title, for the full-library dashboard view.
     *
     * @return every persisted show, alphabetical by title
     */
    List<EnrichedShowDocument> findAllByOrderByTitleAsc();
}

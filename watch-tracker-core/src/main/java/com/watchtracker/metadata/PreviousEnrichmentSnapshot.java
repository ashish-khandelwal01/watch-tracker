package com.watchtracker.metadata;

import java.util.Optional;
import java.util.UUID;

/**
 * Source of a previous enrichment run's results, keyed by the stable show aggregate id.
 *
 * <p>This is the seam between enrichment logic and wherever prior results are stored. The CLI
 * currently backs this with the previous JSON export ({@link JsonEnrichedShowSnapshot}), but a
 * future persistence layer (for example, a MongoDB-backed web service) can implement this
 * interface directly without any change to {@link MetadataEnrichmentService}.
 */
public interface PreviousEnrichmentSnapshot {
    /** A snapshot with no prior history; every show is treated as new. */
    PreviousEnrichmentSnapshot EMPTY = showId -> Optional.empty();

    /**
     * Finds the previously enriched result for a show, if one exists.
     *
     * @param showId stable show aggregate identifier
     * @return the previous enrichment result, if present
     */
    Optional<EnrichedShow> find(UUID showId);
}

package com.watchtracker.metadata;

import com.watchtracker.model.Show;
import java.util.Optional;
import java.util.UUID;

/**
 * Source of a previous enrichment run's results, keyed by the stable show aggregate id and, as a
 * fallback, by title / alias similarity.
 *
 * <p>This is the seam between enrichment logic and wherever prior results are stored. The CLI
 * currently backs this with the previous JSON export ({@link JsonEnrichedShowSnapshot}), but a
 * future persistence layer (for example, a MongoDB-backed web service) can implement this
 * interface directly without any change to {@link MetadataEnrichmentService}.
 *
 * <p>Id lookup is preferred. Title / alias fallback exists so metadata survives grouping changes
 * — most commonly when a manual override that had consolidated episode-level titles is later
 * removed, and the normalizer re-splits those episodes under slightly different canonical titles.
 * Without the fallback the prior {@code shows.json} entry would be orphaned under the old id and
 * the show would fall back to a failed title search ({@code NO_METADATA_MATCH}).
 */
public interface PreviousEnrichmentSnapshot {
    /** A snapshot with no prior history; every show is treated as new. */
    PreviousEnrichmentSnapshot EMPTY = new PreviousEnrichmentSnapshot() {
        @Override
        public Optional<EnrichedShow> find(UUID showId) {
            return Optional.empty();
        }

        @Override
        public Optional<EnrichedShow> findMatching(Show show) {
            return Optional.empty();
        }
    };

    /**
     * Finds the previously enriched result for a show by its stable aggregate id.
     *
     * @param showId stable show aggregate identifier
     * @return the previous enrichment result, if present
     */
    Optional<EnrichedShow> find(UUID showId);

    /**
     * Finds the best prior enrichment result for a show: exact id first, then title / alias /
     * colon-prefix similarity (mirroring {@link MetadataOverrideStore} matching rules).
     *
     * <p>Default implementation only does id lookup. {@link JsonEnrichedShowSnapshot} overrides
     * this with full title/alias search over the loaded export.
     *
     * @param show the current show aggregate
     * @return the best-matching previous enrichment result, if any
     */
    default Optional<EnrichedShow> findMatching(Show show) {
        if (show == null) {
            return Optional.empty();
        }
        return find(show.id());
    }
}

package com.watchtracker.web.persistence;

import com.watchtracker.metadata.EnrichedShow;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Persists an enrichment run's results to MongoDB.
 *
 * <p>The counterpart to {@link MongoPreviousEnrichmentSnapshot}: that class reads what a prior run
 * left behind, this writes what the current run produced, so the next run has something to read.
 * Nothing in this module calls this yet — there is no run-trigger wired into {@code
 * watch-tracker-web} yet (see {@code WatchTrackerWebApplication}'s class doc) — but the write side
 * of the seam needs to exist alongside the read side for either to be verifiable end to end.
 *
 * <p>A Spring {@link Component} (unlike {@link MongoPreviousEnrichmentSnapshot}, which is a plain
 * class with a static factory) because writing has no run-scoped state to hold: each call is
 * self-contained, so there's no staleness risk in letting Spring manage its lifecycle.
 */
@Component
public class MongoEnrichmentWriter {
    private final EnrichedShowMongoRepository repository;
    private final EnrichedShowMongoMapper mapper = new EnrichedShowMongoMapper();

    public MongoEnrichmentWriter(EnrichedShowMongoRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * Upserts every enrichment result from a run. Existing documents for the same show id are
     * replaced in full — there is no partial-field merge, mirroring how {@code
     * WatchHistoryExporter} overwrites {@code shows.json} wholesale rather than patching it.
     *
     * @param enriched the full set of enrichment results from a run
     */
    public void saveAll(List<EnrichedShow> enriched) {
        Objects.requireNonNull(enriched, "enriched must not be null");
        List<EnrichedShowDocument> documents = enriched.stream().map(mapper::toDocument).toList();
        repository.saveAll(documents);
    }
}

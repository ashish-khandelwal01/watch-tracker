package com.watchtracker.web.persistence;

import com.watchtracker.metadata.EnrichedShow;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persists an enrichment run's results to MongoDB.
 *
 * <p>Mirrors CLI {@code shows.json} behavior: each run <b>replaces</b> the library, it does not
 * only upsert. Otherwise shows that disappear after override-driven consolidation (e.g. "Lesson 1"
 * / "Lesson 2" merged into "The Lady's Companion") remain in Mongo forever and the dashboard
 * count drifts above the CLI (~542 vs ~527).
 */
@Component
public class MongoEnrichmentWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MongoEnrichmentWriter.class);

    private final EnrichedShowMongoRepository repository;
    private final EnrichedShowMongoMapper mapper = new EnrichedShowMongoMapper();

    public MongoEnrichmentWriter(EnrichedShowMongoRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * Replaces the entire collection with this run's enrichment results.
     *
     * @param enriched the full set of enrichment results from a run
     */
    public void saveAll(List<EnrichedShow> enriched) {
        Objects.requireNonNull(enriched, "enriched must not be null");
        long previous = repository.count();
        repository.deleteAll();
        List<EnrichedShowDocument> documents = enriched.stream().map(mapper::toDocument).toList();
        repository.saveAll(documents);
        LOGGER.info(
                "Replaced Mongo library: {} previous document(s) → {} saved",
                previous,
                documents.size());
    }
}

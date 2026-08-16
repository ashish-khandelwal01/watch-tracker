package com.watchtracker.web.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.metadata.MatchConfidence;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Converts between {@link EnrichedShow} and {@link EnrichedShowDocument}.
 *
 * <p>Rather than mapping every nested record ({@code Show}, {@code ShowMetadata}, {@code
 * WatchProgress}, their {@code Optional} fields) onto individual Mongo document fields — which
 * would mean re-deriving Spring Data MongoDB conversions for types core already knows how to
 * (de)serialize — this stores the full {@link EnrichedShow} as a JSON payload string, using the
 * same Jackson configuration {@code JsonEnrichedShowSnapshot} and {@code WatchHistoryExporter}
 * already rely on for the file-backed path. A few fields are pulled out of the payload and
 * indexed separately ({@code title}, {@code aliases}, {@code matchConfidence}) purely so Mongo
 * queries and the dashboard don't need to deserialize the payload just to filter or sort.
 *
 * <p>The payload is the source of truth; the indexed fields are a queryable projection of it and
 * must never be trusted over it.
 */
final class EnrichedShowMongoMapper {
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(new Jdk8Module())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Serializes an enrichment result into a document ready to upsert.
     *
     * @param enriched the enrichment result to persist
     * @return a document keyed by the show's stable aggregate id
     */
    EnrichedShowDocument toDocument(EnrichedShow enriched) {
        String payload;
        try {
            payload = mapper.writeValueAsString(enriched);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Failed to serialize EnrichedShow for " + enriched.show().title(), exception);
        }
        MatchConfidence confidence =
                enriched.metadata().map(md -> md.matchConfidence()).filter(Objects::nonNull).orElse(null);
        String matchConfidence = confidence == null ? null : confidence.name();
        return new EnrichedShowDocument(
                enriched.show().id().toString(),
                enriched.show().title(),
                List.copyOf(enriched.show().aliases()),
                matchConfidence,
                payload,
                Instant.now());
    }

    /**
     * Deserializes a stored document back into its {@link EnrichedShow}.
     *
     * <p>A document whose payload no longer parses (e.g. after a breaking model change) is treated
     * the same way {@code JsonEnrichedShowSnapshot} treats an unreadable file: as absent, so the
     * affected show is simply resolved fresh on the next enrichment run rather than aborting it.
     *
     * @param document the stored document
     * @return the enrichment result, or empty if the payload could not be parsed
     */
    Optional<EnrichedShow> toEnrichedShow(EnrichedShowDocument document) {
        try {
            return Optional.of(mapper.readValue(document.payload(), EnrichedShow.class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException exception) {
            return Optional.empty();
        }
    }
}

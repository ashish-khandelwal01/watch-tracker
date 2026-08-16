package com.watchtracker.web.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Persisted form of an {@link com.watchtracker.metadata.EnrichedShow}.
 *
 * <p>{@code id}, {@code title}, {@code aliases}, and {@code matchConfidence} are a queryable
 * projection kept in sync with {@code payload} by {@link EnrichedShowMongoMapper} — see that
 * class for why the payload, not these fields, is the source of truth.
 *
 * <p>{@code title} and {@code matchConfidence} are indexed because {@link
 * EnrichedShowMongoRepository#findAllByOrderByTitleAsc} and {@link
 * EnrichedShowMongoRepository#findByMatchConfidenceNotInOrMatchConfidenceIsNull} sort/filter on
 * them respectively — cheap at today's ~500-document library, worth having before it isn't.
 *
 * @param id the show's stable aggregate id (see {@code Show#id()}), as a string
 * @param title canonical show title, indexed for dashboard listing/search
 * @param aliases raw platform titles observed for this show
 * @param matchConfidence {@code MatchConfidence} name, or {@code null} when no metadata was
 *     matched; kept as a plain string rather than the core enum so this document has no
 *     compile-time dependency on core's enum shape
 * @param payload the full {@code EnrichedShow}, serialized as JSON
 * @param updatedAt when this document was last written
 */
@Document(collection = "enriched_shows")
public record EnrichedShowDocument(
        @Id String id,
        @Indexed String title,
        List<String> aliases,
        @Indexed String matchConfidence,
        String payload,
        Instant updatedAt) {}

/**
 * MongoDB-backed implementation of {@code com.watchtracker.metadata.PreviousEnrichmentSnapshot}.
 *
 * <p>See {@link com.watchtracker.web.persistence.MongoPreviousEnrichmentSnapshot} for the read
 * side of the seam and {@link com.watchtracker.web.persistence.MongoEnrichmentWriter} for the
 * write side. Nothing in this package re-implements import, normalization, or enrichment logic —
 * that all still lives in {@code watch-tracker-core}, per the dependency rule in {@code
 * docs/architecture.md}.
 */
package com.watchtracker.web.persistence;

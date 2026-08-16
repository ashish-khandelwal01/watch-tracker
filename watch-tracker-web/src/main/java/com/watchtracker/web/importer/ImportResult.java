package com.watchtracker.web.importer;

/** Summary after a web-triggered import/enrichment run. */
public record ImportResult(
        int eventCount,
        int showCount,
        int enrichedWithMetadata,
        int reviewCount,
        String message) {}

package com.watchtracker.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.watchtracker.model.ShowType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Writes {@code override_data.json}: machine-generated source of truth for every human confirmation
 * present in an enrichment result ({@link MatchConfidence#MANUAL_OVERRIDE}).
 *
 * <p>Separate from {@code overrides.json}, which is the file a human edits. {@code override_data.json}
 * is rewritten at the end of every metadata run so a backup of all current confirmations always
 * exists — useful after deleting {@code shows.json} or the metadata cache.
 */
public final class OverrideDataExporter {
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public void export(List<EnrichedShow> enriched, Path output) throws IOException {
        Objects.requireNonNull(enriched, "enriched must not be null");
        Objects.requireNonNull(output, "output must not be null");
        mapper.writeValue(output.toFile(), extract(enriched));
    }

    /** Builds the override list without writing a file (for tests). */
    public static List<MetadataOverride> extract(List<EnrichedShow> enriched) {
        Set<String> seen = new LinkedHashSet<>();
        List<MetadataOverride> rows = new ArrayList<>();
        for (EnrichedShow entry : enriched) {
            if (entry.metadata().isEmpty()) {
                continue;
            }
            ShowMetadata md = entry.metadata().get();
            if (md.matchConfidence() != MatchConfidence.MANUAL_OVERRIDE) {
                continue;
            }
            String mediaType = md.type() == ShowType.MOVIE ? "movie" : "tv";
            add(rows, seen, md.title(), md.provider(), mediaType, md.providerId());
            String showTitle = entry.show().title();
            if (showTitle != null
                    && !showTitle.isBlank()
                    && !showTitle.trim().equalsIgnoreCase(md.title().trim())) {
                add(rows, seen, showTitle, md.provider(), mediaType, md.providerId());
            }
        }
        return List.copyOf(rows);
    }

    private static void add(
            List<MetadataOverride> rows,
            Set<String> seen,
            String title,
            String provider,
            String mediaType,
            long providerId) {
        if (title == null || title.isBlank() || provider == null || provider.isBlank() || providerId <= 0) {
            return;
        }
        String key = title.trim().toLowerCase(Locale.ROOT) + "|" + provider.toUpperCase(Locale.ROOT) + "|"
                + mediaType + "|" + providerId;
        if (!seen.add(key)) {
            return;
        }
        rows.add(new MetadataOverride(title.trim(), provider.toUpperCase(Locale.ROOT), mediaType, providerId));
    }
}

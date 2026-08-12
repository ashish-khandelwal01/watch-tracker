package com.watchtracker.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Loads manual metadata corrections from a JSON file, keyed by show title, so {@link
 * MetadataEnrichmentService} can skip title search entirely for shows a human has already
 * resolved.
 *
 * <p>This is deliberately file-based rather than a database: today, a correction means hand-editing
 * this file (using {@link ReviewItem}'s {@code title} and picking a real provider id — the review
 * queue export is the natural source of candidates). Once the planned web module exists, it can
 * write the same file, or a MongoDB-backed equivalent, without this class's one caller needing to
 * change.
 *
 * <p>Overrides are matched by exact show title, not by the show's stable id. Titles are what a
 * human reviewing {@code review-queue.json} actually sees and can copy, whereas the id is an
 * internal UUID with no meaning to a reviewer. The tradeoff: if the title normalizer's output for a
 * show changes in a later code change, a previously-written override silently stops matching (see
 * {@link MetadataEnrichmentService}, which treats a non-matching override as simply
 * absent rather than an error) and the show falls back to normal resolution. This is judged an
 * acceptable, self-healing failure mode for a "for now, manual" mechanism — it degrades to no worse
 * than not having the override at all, rather than silently applying it to the wrong show.
 */
public final class MetadataOverrideStore {
    /** A store with no overrides configured — the default when no override file is provided. */
    public static final MetadataOverrideStore EMPTY = new MetadataOverrideStore(Map.of());

    private final Map<String, MetadataOverride> byTitle;

    private MetadataOverrideStore(Map<String, MetadataOverride> byTitle) {
        this.byTitle = byTitle;
    }

    /**
     * Builds a store directly from a list of overrides, without reading a file. Useful for tests,
     * and for any future caller (e.g. the planned web module) that already has overrides in memory
     * rather than as a JSON file on disk.
     *
     * @param overrides the overrides to index by title
     * @return a store ready for lookups
     */
    public static MetadataOverrideStore of(List<MetadataOverride> overrides) {
        Map<String, MetadataOverride> byTitle = overrides.stream()
                .filter(o -> o.title() != null && !o.title().isBlank())
                .collect(Collectors.toMap(MetadataOverride::title, o -> o, (first, second) -> second));
        return new MetadataOverrideStore(byTitle);
    }

    /**
     * Loads overrides from a JSON file containing an array of {@link MetadataOverride} objects, for
     * example:
     *
     * <pre>{@code
     * [
     *   { "title": "Kingsman", "provider": "TMDB", "mediaType": "movie", "providerId": 9884 }
     * ]
     * }</pre>
     *
     * <p>A missing file is not an error — overrides are entirely opt-in — and returns {@link
     * #EMPTY}. A file that exists but fails to parse is logged to stderr and also treated as {@link
     * #EMPTY}, so a malformed overrides file degrades to "no overrides" rather than aborting the
     * whole import.
     *
     * @param file path to the overrides JSON file, or {@code null} to skip loading entirely
     * @return a store ready for lookups
     */
    public static MetadataOverrideStore load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<MetadataOverride> overrides = mapper.readValue(
                    file.toFile(), mapper.getTypeFactory().constructCollectionType(List.class, MetadataOverride.class));
            return of(overrides);
        } catch (IOException exception) {
            System.err.println("Failed to load metadata overrides from " + file + ": " + exception.getMessage());
            return EMPTY;
        }
    }

    /**
     * Looks up a configured override for a show's exact canonical title.
     *
     * @param title the show's canonical title
     * @return the override, if one is configured for this exact title
     */
    public Optional<MetadataOverride> forTitle(String title) {
        return Optional.ofNullable(byTitle.get(Objects.requireNonNull(title, "title must not be null")));
    }

    /**
     * @return how many overrides are loaded, for CLI reporting
     */
    public int size() {
        return byTitle.size();
    }
}

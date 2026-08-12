package com.watchtracker.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Loads manual metadata corrections from a JSON file, keyed by show title, so {@link
 * MetadataEnrichmentService} can skip title search entirely for shows a human has already
 * resolved.
 *
 * <p>This is deliberately file-based rather than a database: today, a correction means hand-editing
 * this file (using {@link ReviewItem}'s {@code title} / {@code aliases} and picking a real provider
 * id — the review queue export is the natural source of candidates). Once the planned web module
 * exists, it can write the same file, or a MongoDB-backed equivalent, without this class's one
 * caller needing to change.
 *
 * <p>Matching is intentionally flexible so a reviewer can copy either the canonical title or a raw
 * alias from {@code review-queue.json}:
 *
 * <ol>
 *   <li>exact match on the show's canonical title
 *   <li>exact match on any of the show's raw aliases
 *   <li>prefix match: the canonical title starts with {@code overrideTitle + ": "} (so an override
 *       for {@code "The Lady's Companion"} covers {@code "The Lady's Companion: Lesson 1"})
 * </ol>
 *
 * When several of those hit the same show, the longest matching key wins — a full alias override
 * beats a shorter shared show-name override.
 *
 * <p>The tradeoff versus id-keyed overrides: if the title normalizer's output for a show changes
 * in a later code change, a previously-written override may stop matching (see {@link
 * MetadataEnrichmentService}, which treats a non-matching override as simply absent rather than an
 * error) and the show falls back to normal resolution. This is judged an acceptable, self-healing
 * failure mode for a "for now, manual" mechanism — it degrades to no worse than not having the
 * override at all, rather than silently applying it to the wrong show.
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
     * <p>Entries with a blank title or an unrecognized {@code mediaType} are skipped (with a
     * stderr warning for the latter): only {@code "movie"} and {@code "tv"} are accepted, plus
     * {@code "series"} as a synonym for {@code "tv"}. Matching is case-insensitive.
     *
     * @param overrides the overrides to index by title
     * @return a store ready for lookups
     */
    public static MetadataOverrideStore of(List<MetadataOverride> overrides) {
        Map<String, MetadataOverride> byTitle = new LinkedHashMap<>();
        for (MetadataOverride raw : overrides) {
            if (raw.title() == null || raw.title().isBlank()) {
                continue;
            }
            Optional<String> mediaType = normalizeMediaType(raw.mediaType());
            if (mediaType.isEmpty()) {
                System.err.println(
                        "Skipping metadata override for \""
                                + raw.title()
                                + "\": mediaType must be \"movie\" or \"tv\" (got \""
                                + raw.mediaType()
                                + "\")");
                continue;
            }
            String title = raw.title().trim();
            byTitle.put(title, new MetadataOverride(title, raw.provider(), mediaType.get(), raw.providerId()));
        }
        return new MetadataOverrideStore(Map.copyOf(byTitle));
    }

    /**
     * Loads overrides from a JSON file containing an array of {@link MetadataOverride} objects, for
     * example:
     *
     * <pre>{@code
     * [
     *   { "title": "Kingsman", "provider": "TMDB", "mediaType": "movie", "providerId": 9884 },
     *   { "title": "The Lady's Companion", "provider": "TMDB", "mediaType": "tv", "providerId": 280890 },
     *   { "title": "Gabriel Iglesias: Stadium Fluffy", "provider": "TMDB", "mediaType": "movie", "providerId": 123 }
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
     * Looks up a configured override for a show, using exact title, exact alias, or colon-prefix
     * matching (see class docs). Prefer {@link #forShow(String, Collection)} when aliases are
     * available.
     *
     * @param title the show's canonical title
     * @return the override, if one matches this title
     */
    public Optional<MetadataOverride> forTitle(String title) {
        return forShow(title, Set.of());
    }

    /**
     * Looks up a configured override for a show by canonical title and/or raw aliases.
     *
     * @param title the show's canonical title
     * @param aliases the show's raw platform titles (may be empty)
     * @return the best-matching override, if any
     */
    public Optional<MetadataOverride> forShow(String title, Collection<String> aliases) {
        Objects.requireNonNull(title, "title must not be null");
        Collection<String> safeAliases = aliases == null ? Set.of() : aliases;

        MetadataOverride best = null;
        int bestKeyLength = -1;

        MetadataOverride exactTitle = byTitle.get(title);
        if (exactTitle != null) {
            best = exactTitle;
            bestKeyLength = title.length();
        }

        for (String alias : safeAliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            MetadataOverride aliasOverride = byTitle.get(alias);
            if (aliasOverride != null && alias.length() > bestKeyLength) {
                best = aliasOverride;
                bestKeyLength = alias.length();
            }
        }

        for (Map.Entry<String, MetadataOverride> entry : byTitle.entrySet()) {
            String key = entry.getKey();
            if (title.startsWith(key + ": ") && key.length() > bestKeyLength) {
                best = entry.getValue();
                bestKeyLength = key.length();
            }
        }

        return Optional.ofNullable(best);
    }

    /**
     * @return how many overrides are loaded, for CLI reporting
     */
    public int size() {
        return byTitle.size();
    }

    /**
     * Normalizes a media-type string to the values {@link MetadataProvider#findById} understands.
     *
     * @return {@code movie} or {@code tv}, or empty when the value is unrecognized
     */
    static Optional<String> normalizeMediaType(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return Optional.empty();
        }
        String normalized = mediaType.trim().toLowerCase(Locale.ROOT);
        if ("movie".equals(normalized)) {
            return Optional.of("movie");
        }
        if ("tv".equals(normalized) || "series".equals(normalized)) {
            return Optional.of("tv");
        }
        return Optional.empty();
    }
}

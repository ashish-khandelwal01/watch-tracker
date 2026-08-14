package com.watchtracker.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Titles withdrawn from sticky {@link MatchConfidence#MANUAL_OVERRIDE} treatment. Loaded from
 * {@code revoked-overrides.json} (array of strings or objects with a {@code title} field).
 *
 * <p>When a title is revoked it is not re-merged from previous {@code shows.json} into the live
 * override store, and any prior MANUAL_OVERRIDE metadata for that title is demoted to low confidence
 * so the show reappears on the review queue.
 *
 * <p>Matching is case-insensitive exact title, or colon-prefix (revoking {@code "The Lady's
 * Companion"} also covers {@code "The Lady's Companion: Lesson 1"}).
 */
public final class RevokedOverrideStore {
    public static final RevokedOverrideStore EMPTY = new RevokedOverrideStore(Set.of());

    private final Set<String> titlesLower;

    private RevokedOverrideStore(Set<String> titlesLower) {
        this.titlesLower = titlesLower;
    }

    public static RevokedOverrideStore load(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(file.toFile());
            Set<String> titles = new LinkedHashSet<>();
            if (root == null || !root.isArray()) {
                System.err.println("revoked-overrides file must be a JSON array: " + file);
                return EMPTY;
            }
            for (JsonNode node : root) {
                String title = null;
                if (node.isTextual()) {
                    title = node.asText();
                } else if (node.isObject() && node.hasNonNull("title")) {
                    title = node.get("title").asText();
                }
                if (title != null && !title.isBlank()) {
                    titles.add(title.trim().toLowerCase(Locale.ROOT));
                }
            }
            return new RevokedOverrideStore(Set.copyOf(titles));
        } catch (IOException exception) {
            System.err.println("Failed to load revoked overrides from " + file + ": " + exception.getMessage());
            return EMPTY;
        }
    }

    public boolean isEmpty() {
        return titlesLower.isEmpty();
    }

    public int size() {
        return titlesLower.size();
    }

    public boolean isRevoked(String title) {
        return matches(title);
    }

    public boolean isRevoked(String title, Collection<String> aliases) {
        if (isEmpty()) {
            return false;
        }
        if (matches(title)) {
            return true;
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (matches(alias)) {
                    return true;
                }
            }
        }
        return false;
    }

    public ListFilter filterSticky(List<MetadataOverride> sticky) {
        if (sticky == null || sticky.isEmpty() || isEmpty()) {
            return new ListFilter(sticky == null ? List.of() : sticky, 0);
        }
        List<MetadataOverride> kept = new ArrayList<>();
        int dropped = 0;
        for (MetadataOverride o : sticky) {
            if (o != null && matches(o.title())) {
                dropped++;
            } else if (o != null) {
                kept.add(o);
            }
        }
        return new ListFilter(List.copyOf(kept), dropped);
    }

    private boolean matches(String title) {
        if (title == null || title.isBlank() || isEmpty()) {
            return false;
        }
        String t = title.trim().toLowerCase(Locale.ROOT);
        if (titlesLower.contains(t)) {
            return true;
        }
        for (String revoked : titlesLower) {
            if (t.startsWith(revoked + ": ")) {
                return true;
            }
        }
        return false;
    }

    public record ListFilter(List<MetadataOverride> kept, int droppedCount) {
        public ListFilter {
            Objects.requireNonNull(kept);
        }
    }
}

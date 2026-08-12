package com.watchtracker.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.watchtracker.model.Show;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Loads a previous enriched JSON export so an unchanged show can skip re-resolution. */
public final class JsonEnrichedShowSnapshot implements PreviousEnrichmentSnapshot {
    private final Map<UUID, EnrichedShow> byShowId;
    private final List<EnrichedShow> all;

    private JsonEnrichedShowSnapshot(Map<UUID, EnrichedShow> byShowId, List<EnrichedShow> all) {
        this.byShowId = byShowId;
        this.all = all;
    }

    /**
     * Loads a snapshot from a previous enriched JSON export.
     *
     * <p>A missing, unreadable, or malformed file is treated as no prior history rather than a
     * fatal error: the only consequence of a miss is that the affected shows are resolved fresh,
     * exactly as they would be on a first run.
     *
     * @param output path a previous enriched JSON export may already exist at
     * @return a snapshot backed by the file, or an empty snapshot when unavailable
     */
    public static PreviousEnrichmentSnapshot load(Path output) {
        if (output == null || !Files.isRegularFile(output)) {
            return PreviousEnrichmentSnapshot.EMPTY;
        }
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        try {
            List<EnrichedShow> previous = mapper.readValue(output.toFile(), new TypeReference<List<EnrichedShow>>() {});
            Map<UUID, EnrichedShow> byShowId = HashMap.newHashMap(previous.size());
            for (EnrichedShow enrichedShow : previous) {
                byShowId.put(enrichedShow.show().id(), enrichedShow);
            }
            return new JsonEnrichedShowSnapshot(byShowId, List.copyOf(previous));
        } catch (IOException | RuntimeException exception) {
            return PreviousEnrichmentSnapshot.EMPTY;
        }
    }

    @Override
    public Optional<EnrichedShow> find(UUID showId) {
        return Optional.ofNullable(byShowId.get(Objects.requireNonNull(showId, "showId must not be null")));
    }

    /**
     * Id match first; otherwise the prior entry whose title/aliases best overlap the current show.
     * Longest matching title key wins, matching {@link MetadataOverrideStore} precedence so an
     * exact alias beats a shorter shared show-name prefix.
     */
    @Override
    public Optional<EnrichedShow> findMatching(Show show) {
        Objects.requireNonNull(show, "show must not be null");
        Optional<EnrichedShow> byId = find(show.id());
        if (byId.isPresent()) {
            return byId;
        }

        EnrichedShow best = null;
        int bestScore = -1;
        for (EnrichedShow candidate : all) {
            int score = matchScore(show, candidate.show());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore > 0 ? Optional.of(best) : Optional.empty();
    }

    /**
     * Scoring: exact title (largest), exact alias hit, then colon-prefix relationship. Zero means
     * no relationship — the candidate must not be used.
     */
    static int matchScore(Show current, Show prior) {
        String currentTitle = normalizeKey(current.title());
        String priorTitle = normalizeKey(prior.title());
        if (currentTitle.isEmpty() || priorTitle.isEmpty()) {
            return 0;
        }

        if (currentTitle.equals(priorTitle)) {
            return 1_000_000 + currentTitle.length();
        }

        Set<String> currentAliases = current.aliases();
        Set<String> priorAliases = prior.aliases();

        int best = 0;
        for (String alias : currentAliases) {
            String key = normalizeKey(alias);
            if (key.isEmpty()) {
                continue;
            }
            if (key.equals(priorTitle) || containsNormalized(priorAliases, key)) {
                best = Math.max(best, 100_000 + key.length());
            }
        }
        for (String alias : priorAliases) {
            String key = normalizeKey(alias);
            if (key.isEmpty()) {
                continue;
            }
            if (key.equals(currentTitle) || containsNormalized(currentAliases, key)) {
                best = Math.max(best, 100_000 + key.length());
            }
        }

        // "The Lady's Companion: Lesson 1" current ↔ "The Lady's Companion" prior (override removed)
        if (currentTitle.startsWith(priorTitle + ": ")) {
            best = Math.max(best, 10_000 + priorTitle.length());
        }
        // Reverse: current is the short consolidated title, prior still has the episode-level one
        if (priorTitle.startsWith(currentTitle + ": ")) {
            best = Math.max(best, 10_000 + currentTitle.length());
        }

        return best;
    }

    private static boolean containsNormalized(Set<String> aliases, String key) {
        for (String alias : aliases) {
            if (key.equals(normalizeKey(alias))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

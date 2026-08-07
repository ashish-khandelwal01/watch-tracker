package com.watchtracker.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Loads a previous enriched JSON export so an unchanged show can skip re-resolution. */
public final class JsonEnrichedShowSnapshot implements PreviousEnrichmentSnapshot {
    private final Map<UUID, EnrichedShow> byShowId;

    private JsonEnrichedShowSnapshot(Map<UUID, EnrichedShow> byShowId) {
        this.byShowId = byShowId;
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
            return new JsonEnrichedShowSnapshot(byShowId);
        } catch (IOException | RuntimeException exception) {
            return PreviousEnrichmentSnapshot.EMPTY;
        }
    }

    @Override
    public Optional<EnrichedShow> find(UUID showId) {
        return Optional.ofNullable(byShowId.get(Objects.requireNonNull(showId, "showId must not be null")));
    }
}

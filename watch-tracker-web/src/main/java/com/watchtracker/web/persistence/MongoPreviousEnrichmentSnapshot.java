package com.watchtracker.web.persistence;

import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.metadata.JsonEnrichedShowSnapshot;
import com.watchtracker.metadata.MetadataOverride;
import com.watchtracker.metadata.PreviousEnrichmentSnapshot;
import com.watchtracker.model.Show;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MongoDB-backed {@link PreviousEnrichmentSnapshot} — the persistence layer described as "on the
 * horizon" in {@code docs/architecture.md}, now implemented.
 *
 * <p>Mirrors {@link JsonEnrichedShowSnapshot} deliberately closely: same eager-load-once-then-scan
 * shape, same {@link JsonEnrichedShowSnapshot#matchScore} precedence for title/alias fallback
 * matching, same {@link JsonEnrichedShowSnapshot#manualOverridesFrom} override rehydration. The
 * only thing that differs is where the rows come from. This is intentional — {@code
 * MetadataEnrichmentService} depends only on the {@link PreviousEnrichmentSnapshot} interface, so
 * swapping this in for the JSON-backed implementation should require no change there at all.
 *
 * <p>Like {@code JsonEnrichedShowSnapshot.load(Path)}, this is loaded once per enrichment run via
 * {@link #load(EnrichedShowMongoRepository)}, not held open as a long-lived Spring singleton — the
 * data is a run-scoped read, not something that should silently go stale across an app's uptime.
 */
public final class MongoPreviousEnrichmentSnapshot implements PreviousEnrichmentSnapshot {
    private final Map<UUID, EnrichedShow> byShowId;
    private final List<EnrichedShow> all;

    private MongoPreviousEnrichmentSnapshot(Map<UUID, EnrichedShow> byShowId, List<EnrichedShow> all) {
        this.byShowId = byShowId;
        this.all = all;
    }

    /**
     * Loads a snapshot from every document currently in the collection.
     *
     * <p>A document whose payload can no longer be parsed is skipped rather than failing the whole
     * load — see {@link EnrichedShowMongoMapper#toEnrichedShow} — so one bad row can't take down
     * enrichment for every other show.
     *
     * @param repository the Mongo repository to read from
     * @return a snapshot backed by the collection's current contents
     */
    public static PreviousEnrichmentSnapshot load(EnrichedShowMongoRepository repository) {
        Objects.requireNonNull(repository, "repository must not be null");
        EnrichedShowMongoMapper mapper = new EnrichedShowMongoMapper();
        List<EnrichedShowDocument> documents = repository.findAll();
        Map<UUID, EnrichedShow> byShowId = HashMap.newHashMap(documents.size());
        List<EnrichedShow> all = new ArrayList<>(documents.size());
        for (EnrichedShowDocument document : documents) {
            mapper.toEnrichedShow(document).ifPresent(enriched -> {
                all.add(enriched);
                byShowId.put(enriched.show().id(), enriched);
            });
        }
        return new MongoPreviousEnrichmentSnapshot(byShowId, List.copyOf(all));
    }

    @Override
    public Optional<EnrichedShow> find(UUID showId) {
        return Optional.ofNullable(byShowId.get(Objects.requireNonNull(showId, "showId must not be null")));
    }

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
            int score = JsonEnrichedShowSnapshot.matchScore(show, candidate.show());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore > 0 ? Optional.of(best) : Optional.empty();
    }

    @Override
    public List<MetadataOverride> manualOverrides() {
        return JsonEnrichedShowSnapshot.manualOverridesFrom(all);
    }
}

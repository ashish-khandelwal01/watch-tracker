package com.watchtracker.metadata;

import com.watchtracker.model.Show;
import com.watchtracker.model.WatchEvent;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Enriches already-grouped shows without changing raw import data. */
public final class MetadataEnrichmentService {
    private final MetadataProvider provider;

    /**
     * Creates a service using the supplied metadata provider.
     *
     * @param provider provider used to retrieve metadata
     */
    public MetadataEnrichmentService(MetadataProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
    }

    /**
     * Looks up metadata for every show. A provider miss is retained as an empty result.
     *
     * @param shows show aggregates to enrich
     * @return enrichment results in the same order as the input
     */
    public List<EnrichedShow> enrich(List<Show> shows) {
        Objects.requireNonNull(shows, "shows must not be null");
        return shows.stream()
                .map(show -> new EnrichedShow(show, provider.findByTitle(show.title()), Optional.empty()))
                .toList();
    }

    /**
     * Looks up metadata and resolves the highest episode position from normalized watch events.
     * Named episodes are resolved through the configured provider, while Netflix episode numbers
     * remain authoritative and do not require an external lookup.
     *
     * @param shows show aggregates to enrich
     * @param events normalized source watch events
     * @return enrichment results with optional highest resolved progress
     */
    public List<EnrichedShow> enrich(List<Show> shows, List<WatchEvent> events) {
        Objects.requireNonNull(shows, "shows must not be null");
        Objects.requireNonNull(events, "events must not be null");
        return shows.stream()
                .map(show -> new EnrichedShow(show, provider.findByTitle(show.title()), highestProgress(show, events)))
                .toList();
    }

    /** Determines the highest known provider or Netflix episode position for one show. */
    private Optional<WatchProgress> highestProgress(Show show, List<WatchEvent> events) {
        return events.stream()
                .filter(event -> show.title().equals(event.canonicalShow()))
                .map(event -> resolve(event, show.title()))
                .flatMap(Optional::stream)
                .max(WatchProgress::compareTo);
    }

    /** Resolves one normalized watch event without inferring a number that cannot be verified. */
    private Optional<WatchProgress> resolve(WatchEvent event, String showTitle) {
        if (event.season() == null) {
            return Optional.empty();
        }
        if (event.episode() != null) {
            return Optional.of(new WatchProgress(event.season(), event.episode(), "NETFLIX"));
        }
        return event.episodeTitle() == null
                ? Optional.empty()
                : provider.resolveEpisode(showTitle, event.season(), event.episodeTitle());
    }
}

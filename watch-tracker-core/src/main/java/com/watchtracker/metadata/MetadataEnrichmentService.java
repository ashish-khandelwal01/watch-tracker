package com.watchtracker.metadata;

import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.DiagnosticsStage;
import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.model.Show;
import com.watchtracker.model.WatchEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** Enriches already-grouped shows without changing raw import data. */
public final class MetadataEnrichmentService {
    private final MetadataProvider provider;
    private final DiagnosticsCollector diagnostics;

    /**
     * Creates a service using the supplied metadata provider.
     *
     * @param provider provider used to retrieve metadata
     */
    public MetadataEnrichmentService(MetadataProvider provider) {
        this(provider, new NoOpDiagnosticsCollector());
    }

    /**
     * Creates a service using the supplied metadata provider and diagnostics collector.
     *
     * @param provider provider used to retrieve metadata
     * @param diagnostics diagnostics collector for recording resolution steps
     */
    public MetadataEnrichmentService(MetadataProvider provider, DiagnosticsCollector diagnostics) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
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
        return enrich(shows, events, PreviousEnrichmentSnapshot.EMPTY);
    }

    /**
     * Looks up metadata and resolves the highest episode position, reusing a previous run's
     * result for any show whose imported data has not changed since that run.
     *
     * <p>A show is considered unchanged when its watch count, highest known season, highest known
     * episode, and set of raw title aliases all match the previous result. Any discrepancy in
     * those fields means new watch history could shift the resolved episode position or the
     * matched provider title, so the show is resolved fresh.
     *
     * @param shows show aggregates to enrich
     * @param events normalized source watch events
     * @param previous a previous run's results, consulted before any provider call
     * @return enrichment results with optional highest resolved progress
     */
    public List<EnrichedShow> enrich(List<Show> shows, List<WatchEvent> events, PreviousEnrichmentSnapshot previous) {
        Objects.requireNonNull(shows, "shows must not be null");
        Objects.requireNonNull(events, "events must not be null");
        Objects.requireNonNull(previous, "previous must not be null");
        return shows.stream().map(show -> enrichOne(show, events, previous)).toList();
    }

    /** Reuses a previous result when nothing that could affect it has changed, else resolves fresh. */
    private EnrichedShow enrichOne(Show show, List<WatchEvent> events, PreviousEnrichmentSnapshot previous) {
        return previous.find(show.id())
                .filter(prior -> unchangedSincePrevious(show, prior.show()))
                .map(prior -> new EnrichedShow(show, prior.metadata(), prior.progress()))
                .orElseGet(() -> {
                    Optional<ShowMetadata> metadata = provider.findByTitle(show.title());

                    if (metadata.isPresent()) {
                        diagnostics.success(
                                DiagnosticsStage.METADATA_PROVIDER,
                                show.title(),
                                "Matched metadata",
                                null,
                                java.util.Map.of(
                                        "provider",
                                        metadata.get().provider(),
                                        "id",
                                        metadata.get().providerId()));
                    } else {
                        diagnostics.failure(
                                DiagnosticsStage.METADATA_PROVIDER, show.title(), "No metadata match found");
                    }

                    Optional<WatchProgress> progress = highestProgress(show, events);
                    return new EnrichedShow(show, metadata, progress);
                });
    }

    /** True when nothing that could affect provider resolution has changed since the previous run. */
    private static boolean unchangedSincePrevious(Show current, Show previous) {
        return current.watchCount() == previous.watchCount()
                && Objects.equals(current.highestSeasonSeen(), previous.highestSeasonSeen())
                && Objects.equals(current.highestEpisodeSeen(), previous.highestEpisodeSeen())
                && current.aliases().equals(previous.aliases());
    }

    /**
     * Determines the highest known provider or Netflix episode position for one show.
     *
     * <p>Netflix-numbered events resolve for free from the imported data. Named episodes require a
     * provider lookup, so duplicate rows (rewatches, autoplay re-entries, etc.) are collapsed to one
     * lookup per distinct (season, episode title) pair before any provider call is made.
     */
    private Optional<WatchProgress> highestProgress(Show show, List<WatchEvent> events) {
        List<WatchEvent> showEvents = events.stream()
                .filter(event -> show.title().equals(event.canonicalShow()))
                .toList();

        Optional<WatchProgress> bestNumbered = showEvents.stream()
                .filter(event -> event.season() != null && event.episode() != null)
                .map(event -> new WatchProgress(event.season(), event.episode(), "NETFLIX"))
                .max(WatchProgress::compareTo);

        if (bestNumbered.isPresent()) {
            diagnostics.success(
                    DiagnosticsStage.PROGRESS_BUILDER,
                    show.title(),
                    "Highest numbered episode",
                    null,
                    java.util.Map.of(
                            "season",
                            bestNumbered.get().season(),
                            "episode",
                            bestNumbered.get().episode()));
        }

        Optional<WatchProgress> bestNamed = uniqueNamedEpisodes(showEvents).stream()
                .peek(named -> {
                    Optional<WatchProgress> resolved =
                            provider.resolveEpisode(show.title(), named.season(), named.episodeTitle());
                    if (resolved.isPresent()) {
                        diagnostics.success(
                                DiagnosticsStage.EPISODE_RESOLVER,
                                show.title(),
                                "Episode title resolved",
                                java.util.Map.of("season", named.season(), "episodeTitle", named.episodeTitle()),
                                java.util.Map.of(
                                        "season",
                                        resolved.get().season(),
                                        "episode",
                                        resolved.get().episode(),
                                        "provider",
                                        resolved.get().provider()));
                    } else {
                        diagnostics.failure(
                                DiagnosticsStage.EPISODE_RESOLVER,
                                show.title(),
                                "Episode title not found",
                                java.util.Map.of("season", named.season(), "episodeTitle", named.episodeTitle()));
                    }
                })
                .map(named -> provider.resolveEpisode(show.title(), named.season(), named.episodeTitle()))
                .flatMap(Optional::stream)
                .max(WatchProgress::compareTo);

        return Stream.of(bestNumbered, bestNamed).flatMap(Optional::stream).max(WatchProgress::compareTo);
    }

    /** Collapses events sharing a season and episode title so each is only resolved once. */
    private static Set<NamedEpisode> uniqueNamedEpisodes(List<WatchEvent> showEvents) {
        Set<NamedEpisode> unique = new LinkedHashSet<>();
        for (WatchEvent event : showEvents) {
            if (event.season() != null && event.episode() == null && event.episodeTitle() != null) {
                unique.add(new NamedEpisode(event.season(), event.episodeTitle()));
            }
        }
        return unique;
    }

    /** A distinct season and named-episode-title pair awaiting provider resolution. */
    private record NamedEpisode(int season, String episodeTitle) {}
}

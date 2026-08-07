package com.watchtracker.metadata;

import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.DiagnosticsStage;
import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import com.watchtracker.model.WatchEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
        Optional<EnrichedShow> priorOpt = previous.find(show.id());
        if (priorOpt.isPresent()) {
            EnrichedShow prior = priorOpt.get();
            if (unchangedSincePrevious(show, prior.show())) {
                // If the prior snapshot contains no metadata AND no resolved progress, resolve fresh.
                if (prior.metadata().isEmpty() && prior.progress().isEmpty()) {
                    diagnostics.info(
                            DiagnosticsStage.METADATA_PROVIDER,
                            show.title(),
                            "Previous snapshot present and unchanged but missing metadata and progress; resolving fresh");
                } else {
                    // Emit diagnostics for cached metadata so diagnostics output reflects reused data.
                    if (prior.metadata().isPresent()) {
                        ShowMetadata md = prior.metadata().get();
                        diagnostics.success(
                                DiagnosticsStage.METADATA_PROVIDER,
                                show.title(),
                                "Reused cached metadata",
                                null,
                                java.util.Map.of("provider", md.provider(), "id", md.providerId()));
                    } else {
                        diagnostics.failure(DiagnosticsStage.METADATA_PROVIDER, show.title(), "No cached metadata");
                    }

                    // Emit diagnostics for cached resolved progress when present.
                    if (prior.progress().isPresent()) {
                        WatchProgress wp = prior.progress().get();
                        diagnostics.success(
                                DiagnosticsStage.PROGRESS_BUILDER,
                                show.title(),
                                "Reused cached highest resolved progress",
                                null,
                                java.util.Map.of(
                                        "season", wp.season(), "episode", wp.episode(), "provider", wp.provider()));
                    }

                    return new EnrichedShow(show, prior.metadata(), prior.progress());
                }
            } else {
                // Explain why the prior snapshot was not reused for easier debugging.
                diagnostics.info(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        "Previous snapshot present but show data changed; resolving fresh");
                diagnostics.success(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        "Previous vs current",
                        java.util.Map.of(
                                "previousHighestSeason",
                                prior.show().highestSeasonSeen(),
                                "previousHighestEpisode",
                                prior.show().highestEpisodeSeen(),
                                "previousWatchCount",
                                prior.show().watchCount(),
                                "previousAliases",
                                prior.show().aliases()),
                        java.util.Map.of(
                                "currentHighestSeason",
                                show.highestSeasonSeen(),
                                "currentHighestEpisode",
                                show.highestEpisodeSeen(),
                                "currentWatchCount",
                                show.watchCount(),
                                "currentAliases",
                                show.aliases()));
            }
        }

        // Fall through to fresh resolution when no prior snapshot or it differed.
        String originalTitle = show.title();
        boolean matchedAfterStrip = false;
        Optional<ShowMetadata> metadata = Optional.empty();

        // First, consult the persistent cache only (if available) for the original and stripped titles.
        if (provider instanceof CachingMetadataProvider cmp) {
            metadata = cmp.getFromCache(originalTitle);
            if (metadata.isEmpty() && originalTitle.toLowerCase(Locale.ROOT).contains("limited series")) {
                String cleaned = stripLimitedSeries(originalTitle);
                if (!cleaned.isBlank() && !cleaned.equals(originalTitle)) {
                    metadata = cmp.getFromCache(cleaned);
                    if (metadata.isPresent()) {
                        matchedAfterStrip = true;
                    }
                }
            }
        }

        // If the cache had no entry, fall back to the provider which will consult cache and delegate.
        if (metadata.isEmpty()) {
            metadata = provider.findByTitle(originalTitle);
            if (metadata.isEmpty() && originalTitle.toLowerCase(Locale.ROOT).contains("limited series")) {
                String cleaned = stripLimitedSeries(originalTitle);
                if (!cleaned.isBlank() && !cleaned.equals(originalTitle)) {
                    metadata = provider.findByTitle(cleaned);
                    matchedAfterStrip = metadata.isPresent();
                }
            }
        }

        if (metadata.isPresent()) {
            if (matchedAfterStrip) {
                diagnostics.success(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        "Matched metadata after stripping 'Limited Series'",
                        null,
                        java.util.Map.of(
                                "provider",
                                metadata.get().provider(),
                                "id",
                                metadata.get().providerId(),
                                "searchedTitle",
                                stripLimitedSeries(originalTitle)));
            } else {
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
            }
        } else {
            diagnostics.failure(DiagnosticsStage.METADATA_PROVIDER, show.title(), "No metadata match found");
        }

        Optional<WatchProgress> progress = highestProgress(show, events);
        // If metadata present, prefer provider type; if provider marks as SERIES but title/aliases
        // contain 'Limited Series', treat it as LIMITED_SERIES.
        Show finalShow = show;
        if (metadata.isPresent()) {
            ShowMetadata md = metadata.get();
            ShowType mdType = md.type();
            boolean hasLimited = show.title().toLowerCase(Locale.ROOT).contains("limited series")
                    || show.aliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains("limited series"));
            boolean hasDocumentaryGenre = md.genres().stream()
                    .filter(g -> g != null)
                    .anyMatch(g -> g.toLowerCase(Locale.ROOT).contains("documentary"));
            if (hasDocumentaryGenre) {
                mdType = ShowType.DOCUMENTARY;
            } else if (mdType == ShowType.SERIES && hasLimited) {
                mdType = ShowType.LIMITED_SERIES;
            }
            finalShow = new Show(
                    show.id(),
                    show.title(),
                    show.aliases(),
                    mdType,
                    show.firstWatched(),
                    show.lastWatched(),
                    show.highestSeasonSeen(),
                    show.highestEpisodeSeen(),
                    show.watchCount());
        }
        return new EnrichedShow(finalShow, metadata, progress);

    }

    private static final Pattern LIMITED_SERIES_TRAILING = Pattern.compile("(?i)\\s*:?\\s*limited series\\b\\s*$");

    private static String stripLimitedSeries(String title) {
        if (title == null) return null;
        return LIMITED_SERIES_TRAILING.matcher(title).replaceAll("").trim();
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
                .filter(event -> event.episode() != null)
                .map(event -> {
                    int season = event.season() != null ? event.season() : 1;
                    return new WatchProgress(season, event.episode(), "NETFLIX");
                })
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
                .map(named -> {
                    Optional<WatchProgress> resolved = named.season() != null
                            ? provider.resolveEpisode(show.title(), named.season(), named.episodeTitle())
                            : provider.resolveEpisodeAnySeason(show.title(), named.episodeTitle());
                    java.util.Map<String, Object> searched = named.season() != null
                            ? java.util.Map.of("season", named.season(), "episodeTitle", named.episodeTitle())
                            : java.util.Map.of(
                            "season", "any", "episodeTitle", named.episodeTitle());
                    if (resolved.isPresent()) {
                        diagnostics.success(
                                DiagnosticsStage.EPISODE_RESOLVER,
                                show.title(),
                                "Episode title resolved",
                                searched,
                                java.util.Map.of(
                                        "season",
                                        resolved.get().season(),
                                        "episode",
                                        resolved.get().episode(),
                                        "provider",
                                        resolved.get().provider()));
                    } else {
                        diagnostics.failure(
                                DiagnosticsStage.EPISODE_RESOLVER, show.title(), "Episode title not found", searched);
                    }
                    return resolved;
                })
                .flatMap(Optional::stream)
                .max(WatchProgress::compareTo);

        return Stream.of(bestNumbered, bestNamed).flatMap(Optional::stream).max(WatchProgress::compareTo);
    }

    /**
     * Collapses events sharing an episode title so each is only resolved once. Events whose
     * season could not be normalized (the source title carried no season or container marker) are
     * still included with a {@code null} season, so they are resolved via {@link
     * MetadataProvider#resolveEpisodeAnySeason} instead of being silently dropped.
     */
    private static Set<NamedEpisode> uniqueNamedEpisodes(List<WatchEvent> showEvents) {
        Set<NamedEpisode> unique = new LinkedHashSet<>();
        for (WatchEvent event : showEvents) {
            if (event.episode() == null && event.episodeTitle() != null) {
                unique.add(new NamedEpisode(event.season(), event.episodeTitle()));
            }
        }
        return unique;
    }

    /** A distinct named-episode-title pair awaiting provider resolution, with an optional season. */
    private record NamedEpisode(Integer season, String episodeTitle) {}
}
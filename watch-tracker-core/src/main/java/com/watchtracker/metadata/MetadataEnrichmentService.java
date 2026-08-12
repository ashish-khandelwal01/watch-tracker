package com.watchtracker.metadata;

import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.DiagnosticsStage;
import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.model.CompletionStatus;
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

/** Enriches already-grouped shows without changing raw import data. */
public final class MetadataEnrichmentService {
    private final MetadataProvider provider;
    private final DiagnosticsCollector diagnostics;
    private final MetadataOverrideStore overrides;
    // A single show taking this long almost always means a provider call stalled/retried rather
    // than normal processing cost — see enrich(...) for how this is used.
    private static final long SLOW_SHOW_THRESHOLD_MILLIS = 5_000;
    private static final int PROGRESS_LOG_INTERVAL = 25;

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
        this(provider, diagnostics, MetadataOverrideStore.EMPTY);
    }

    /**
     * Creates a service using the supplied metadata provider, diagnostics collector, and manual
     * overrides.
     *
     * @param provider provider used to retrieve metadata
     * @param diagnostics diagnostics collector for recording resolution steps
     * @param overrides human-confirmed corrections consulted before any search, cache, or prior
     *     snapshot — see {@link MetadataOverrideStore}
     */
    public MetadataEnrichmentService(
            MetadataProvider provider, DiagnosticsCollector diagnostics, MetadataOverrideStore overrides) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        this.overrides = Objects.requireNonNull(overrides, "overrides must not be null");
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
                .map(show -> new EnrichedShow(show, findByTitleSafely(show.title()), Optional.empty()))
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
        List<EnrichedShow> results = new java.util.ArrayList<>(shows.size());
        long runStart = System.nanoTime();
        for (int i = 0; i < shows.size(); i++) {
            Show show = shows.get(i);
            long showStart = System.nanoTime();
            EnrichedShow result = enrichOne(show, events, previous);
            long showMillis = (System.nanoTime() - showStart) / 1_000_000;
            double totalElapsedSeconds = (System.nanoTime() - runStart) / 1_000_000_000.0;
            // A per-show duration this long almost always means a provider call stalled or was
            // retried against a slow/throttled endpoint rather than the show itself being expensive
            // to process — flag it plainly so a long run can be attributed to specific shows instead
            // of looking like uniform, unexplained slowness.
            if (showMillis >= SLOW_SHOW_THRESHOLD_MILLIS) {
                System.err.printf(
                        "[%d/%d] SLOW SHOW: '%s' took %d ms (%.1f s elapsed total)%n",
                        i + 1, shows.size(), show.title(), showMillis, totalElapsedSeconds);
            } else if ((i + 1) % PROGRESS_LOG_INTERVAL == 0 || i + 1 == shows.size()) {
                System.err.printf("[%d/%d] enriched (%.1f s elapsed)%n", i + 1, shows.size(), totalElapsedSeconds);
            }
            results.add(result);
        }
        return results;
    }

    /** Reuses a previous result when nothing that could affect it has changed, else resolves fresh. */
    private EnrichedShow enrichOne(Show show, List<WatchEvent> events, PreviousEnrichmentSnapshot previous) {
        // A manual override represents a human already confirming (or correcting) which provider
        // entity this show is, so it takes precedence over everything else: a reused prior
        // snapshot, the persistent cache, and a fresh search would all just reproduce the mismatch
        // the override exists to fix. Checked first, before even consulting the prior snapshot, so
        // a stale wrong snapshot can never outlive a correction.
        Optional<ShowMetadata> overrideMetadata = resolveOverride(show.title());
        if (overrideMetadata.isPresent()) {
            ShowMetadata md = overrideMetadata.get();
            diagnostics.success(
                    DiagnosticsStage.METADATA_PROVIDER,
                    show.title(),
                    "Matched metadata from manual override",
                    null,
                    java.util.Map.of("provider", md.provider(), "id", md.providerId()));
        }

        Optional<EnrichedShow> priorOpt = overrideMetadata.isPresent() ? Optional.empty() : previous.find(show.id());
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
                        java.util.Map<String, Object> progressOut = new java.util.LinkedHashMap<>();
                        if (wp.season() != null) {
                            progressOut.put("season", wp.season());
                        }
                        if (wp.episode() != null) {
                            progressOut.put("episode", wp.episode());
                        }
                        progressOut.put("provider", wp.provider());
                        progressOut.put("type", wp.type());
                        diagnostics.success(
                                DiagnosticsStage.PROGRESS_BUILDER,
                                show.title(),
                                "Reused cached highest resolved progress",
                                null,
                                progressOut);
                    }

                    Show finalShow = show;
                    if (prior.progress().isPresent()) {
                        // prior.progress().type() is the fully-resolved type from whenever this show
                        // was last freshly resolved — already accounting for the documentary/
                        // limited-series adjustments a fresh resolution applies — so reuse it directly
                        // rather than re-deriving it here, keeping the two paths in sync by
                        // construction instead of by keeping duplicated derivation logic consistent.
                        finalShow = withType(show, prior.progress().get().type());
                    } else if (prior.metadata().isPresent()) {
                        finalShow = withType(show, prior.metadata().get().type());
                    }
                    return new EnrichedShow(finalShow, prior.metadata(), prior.progress());
                }
            } else {
                // Explain why the prior snapshot was not reused for easier debugging.
                diagnostics.info(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        "Previous snapshot present but show data changed; resolving fresh");
                java.util.Map<String, Object> previousState = new java.util.LinkedHashMap<>();
                previousState.put("previousHighestSeason", prior.show().highestSeasonSeen());
                previousState.put("previousHighestEpisode", prior.show().highestEpisodeSeen());
                previousState.put("previousWatchCount", prior.show().watchCount());
                previousState.put("previousAliases", prior.show().aliases());
                java.util.Map<String, Object> currentState = new java.util.LinkedHashMap<>();
                currentState.put("currentHighestSeason", show.highestSeasonSeen());
                currentState.put("currentHighestEpisode", show.highestEpisodeSeen());
                currentState.put("currentWatchCount", show.watchCount());
                currentState.put("currentAliases", show.aliases());
                diagnostics.success(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        "Previous vs current",
                        previousState,
                        currentState);
            }
        }

        // Fall through to fresh resolution when no prior snapshot or it differed.
        String originalTitle = show.title();
        boolean matchedAfterStrip = false;
        Optional<ShowMetadata> metadata = overrideMetadata;

        // First, consult the persistent cache only (if available) for the original and stripped titles.
        // Skipped entirely when an override already supplied metadata — a human's explicit correction
        // should never be second-guessed by a title-keyed cache entry that predates it.
        if (metadata.isEmpty() && provider instanceof CachingMetadataProvider cmp) {
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
        // Provider timeouts/failures after retries must not abort the whole import: treat them as
        // a miss so this show is left without metadata and the rest of the run continues.
        if (metadata.isEmpty()) {
            metadata = findByTitleSafely(originalTitle);
            if (metadata.isEmpty() && originalTitle.toLowerCase(Locale.ROOT).contains("limited series")) {
                String cleaned = stripLimitedSeries(originalTitle);
                if (!cleaned.isBlank() && !cleaned.equals(originalTitle)) {
                    metadata = findByTitleSafely(cleaned);
                    matchedAfterStrip = metadata.isPresent();
                }
            }
        }

        // A generic "Show: Subtitle" split (the fallback used when no season/part/chapter marker is
        // present — see TitleNormalizer) is inherently ambiguous between a real episode title and a
        // movie's own subtitle, e.g. "G.I. Joe: Retaliation" splits into show "G.I. Joe" + episode
        // "Retaliation" even though the whole thing is one movie title. When the truncated "show"
        // name alone resolves to a movie, that is itself a strong signal the split ate a movie
        // subtitle (a real TV show's bare franchise name doesn't usually resolve to a movie), and the
        // truncated name is too generic for a reliable lookup — "G.I. Joe" alone is ambiguous with an
        // unrelated animated series. Retrying with the full, untruncated raw title recovers the real,
        // specific movie.
        //
        // This is only applied when there is a single raw alias. With more than one alias, the split
        // may have merged multiple distinct movies under one shared franchise prefix (e.g. two
        // different "Kingsman" films both losing their subtitles to this same split), and a
        // single retry title can't safely disambiguate which movie is which — that case needs the
        // shows to be un-merged at grouping time, which this per-show retry deliberately does not
        // attempt.
        //
        // Also skipped entirely when the metadata came from a manual override: a human already
        // identified the exact right entity, so retrying with a different title risks overriding
        // the override with a worse guess.
        boolean retriedWithFullTitle = false;
        if (overrideMetadata.isEmpty()
                && metadata.isPresent()
                && metadata.get().type() == ShowType.MOVIE
                && show.aliases().size() == 1) {
            String fullRawTitle = show.aliases().iterator().next();
            if (!fullRawTitle.equals(originalTitle)) {
                Optional<ShowMetadata> fullTitleMetadata = findByTitleSafely(fullRawTitle);
                if (fullTitleMetadata.isPresent() && fullTitleMetadata.get().type() == ShowType.MOVIE) {
                    metadata = fullTitleMetadata;
                    matchedAfterStrip = false;
                    retriedWithFullTitle = true;
                }
            }
        }

        ShowType mdType = show.type();
        if (metadata.isPresent()) {
            ShowMetadata md = metadata.get();
            // Residual C: seed title → id so any later title-based episode resolve in this run
            // reuses the matched series instead of ranking a different search hit first.
            seedSeriesMappings(originalTitle, md);
            mdType = md.type();
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
            if (overrideMetadata.isPresent()) {
                // Already logged once, right when the override was detected — avoid a redundant
                // second "matched" entry for the same show.
            } else if (retriedWithFullTitle) {
                diagnostics.success(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        "Matched metadata after retrying with full raw title (split title resolved to a movie)",
                        null,
                        java.util.Map.of(
                                "provider",
                                md.provider(),
                                "id",
                                md.providerId(),
                                "searchedTitle",
                                show.aliases().iterator().next()));
            } else if (matchedAfterStrip) {
                diagnostics.success(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        "Matched metadata after stripping 'Limited Series'",
                        null,
                        java.util.Map.of(
                                "provider",
                                md.provider(),
                                "id",
                                md.providerId(),
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
                                md.provider(),
                                "id",
                                md.providerId()));
            }
        } else {
            diagnostics.failure(DiagnosticsStage.METADATA_PROVIDER, show.title(), "No metadata match found");
        }

        ShowType progressType = (mdType == ShowType.DOCUMENTARY && metadata.isPresent() && metadata.get().type() == ShowType.MOVIE)
                ? ShowType.MOVIE
                : mdType;
        Optional<WatchProgress> progress = highestProgress(show, events, progressType, metadata);
        Show finalShow = metadata.isPresent() ? withType(show, mdType) : show;
        return new EnrichedShow(finalShow, metadata, progress);

    }

    /** Returns a copy of {@code show} with its {@code type} replaced, all other fields unchanged. */
    private static Show withType(Show show, ShowType type) {
        return new Show(
                show.id(),
                show.title(),
                show.aliases(),
                type,
                show.firstWatched(),
                show.lastWatched(),
                show.highestSeasonSeen(),
                show.highestEpisodeSeen(),
                show.watchCount());
    }

    private static final Pattern LIMITED_SERIES_TRAILING = Pattern.compile("(?i)\\s*:?\\s*limited series\\b\\s*$");

    private static String stripLimitedSeries(String title) {
        if (title == null) return null;
        return LIMITED_SERIES_TRAILING.matcher(title).replaceAll("").trim();
    }

    /**
     * Looks up metadata for a title. Any provider failure (timeout after retries, HTTP error,
     * etc.) is treated as a miss so a single unreachable API cannot abort the entire import.
     */
    private Optional<ShowMetadata> findByTitleSafely(String title) {
        try {
            return provider.findByTitle(title);
        } catch (RuntimeException exception) {
            diagnostics.failure(
                    DiagnosticsStage.METADATA_PROVIDER,
                    title,
                    "Metadata provider failed: " + exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Resolves a manual override for a show's title, if one is configured, bypassing title search
     * entirely in favor of the human-confirmed provider entity.
     *
     * <p>A configured-but-unresolvable override (a typo'd id, a since-removed entry, a transient
     * provider failure) is treated as absent rather than propagated as an error: this show simply
     * falls through to the normal resolution path, the same as if no override existed, rather than
     * the whole import aborting over one bad correction.
     */
    private Optional<ShowMetadata> resolveOverride(String title) {
        Optional<MetadataOverride> override = overrides.forTitle(title);
        if (override.isEmpty()) {
            return Optional.empty();
        }
        MetadataOverride o = override.get();
        try {
            Optional<ShowMetadata> resolved = provider.findById(o.provider(), o.mediaType(), o.providerId());
            if (resolved.isEmpty()) {
                diagnostics.failure(
                        DiagnosticsStage.METADATA_PROVIDER,
                        title,
                        "Manual override configured but did not resolve (provider="
                                + o.provider()
                                + ", id="
                                + o.providerId()
                                + "); falling back to normal resolution");
            }
            return resolved;
        } catch (RuntimeException exception) {
            diagnostics.failure(
                    DiagnosticsStage.METADATA_PROVIDER,
                    title,
                    "Manual override lookup failed: " + exception.getMessage() + "; falling back to normal resolution");
            return Optional.empty();
        }
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
    private Optional<WatchProgress> highestProgress(Show show, List<WatchEvent> events, ShowType resolvedType, Optional<ShowMetadata> metadata) {
        if (resolvedType == ShowType.MOVIE) {
            List<WatchEvent> showEvents = events.stream()
                    .filter(event -> show.title().equals(event.canonicalShow()))
                    .toList();
            if (!showEvents.isEmpty()) {
                String providerName = metadata.isPresent() ? metadata.get().provider() : "NETFLIX";
                // A single watch is definitionally the whole movie, so this is mechanically
                // COMPLETED — a real fact, not a stand-in for whatever the user's own WatchStatus
                // on UserShow might eventually say (they could still mark it DROPPED after
                // watching, e.g. if they hated it; that's their call, not this record's).
                return Optional.of(new WatchProgress(ShowType.MOVIE, null, null, providerName, CompletionStatus.COMPLETED));
            }
            return Optional.empty();
        }

        List<WatchEvent> showEvents = events.stream()
                .filter(event -> show.title().equals(event.canonicalShow()))
                .toList();

        ShowType progType = (resolvedType == null || resolvedType == ShowType.UNKNOWN) ? ShowType.SERIES : resolvedType;

        // Every distinct (season, episode) position resolved for this show, regardless of whether
        // it came from Netflix's own numbering or a provider-resolved named episode. Completion
        // status is derived from how many *distinct* episodes have been watched, not from the
        // highest position reached: someone who watched all of season 1 and then skipped straight
        // to season 3 episode 1 has a high position but has only watched a fraction of the show,
        // and a running max alone would not catch that. Numbered and named positions are merged
        // into the same map (keyed purely by season+episode, not by source) so the same real
        // episode is only counted once even if it happened to be seen both ways.
        java.util.Map<EpisodeKey, String> resolvedPositions = new java.util.LinkedHashMap<>();
        WatchProgress bestPosition = null;

        for (WatchEvent event : showEvents) {
            if (event.episode() == null) {
                continue;
            }
            int season = event.season() != null ? event.season() : 1;
            resolvedPositions.putIfAbsent(new EpisodeKey(season, event.episode()), "NETFLIX");
            // UNKNOWN here is a placeholder: the real, count-derived status is computed once below,
            // after every numbered and named position for this show has been collected.
            WatchProgress candidate = new WatchProgress(progType, season, event.episode(), "NETFLIX", CompletionStatus.UNKNOWN);
            if (bestPosition == null || candidate.compareTo(bestPosition) > 0) {
                bestPosition = candidate;
            }
        }

        if (bestPosition != null) {
            java.util.Map<String, Object> numberedOut = new java.util.LinkedHashMap<>();
            numberedOut.put("season", bestPosition.season());
            numberedOut.put("episode", bestPosition.episode());
            numberedOut.put("provider", bestPosition.provider());
            diagnostics.success(
                    DiagnosticsStage.PROGRESS_BUILDER, show.title(), "Highest numbered episode", null, numberedOut);
        }

        for (NamedEpisode named : uniqueNamedEpisodes(showEvents)) {
            Optional<WatchProgress> resolved = resolveNamedEpisode(show, named, progType, metadata);
            if (resolved.isEmpty()) {
                continue;
            }
            WatchProgress wp = resolved.get();
            resolvedPositions.putIfAbsent(new EpisodeKey(wp.season(), wp.episode()), wp.provider());
            if (bestPosition == null || wp.compareTo(bestPosition) > 0) {
                bestPosition = wp;
            }
        }

        if (bestPosition == null) {
            return Optional.empty();
        }

        int distinctWatched = resolvedPositions.size();
        CompletionStatus status = completionStatus(distinctWatched, metadata);
        WatchProgress finalProgress = new WatchProgress(
                progType, bestPosition.season(), bestPosition.episode(), bestPosition.provider(), status);

        java.util.Map<String, Object> statusInput = new java.util.LinkedHashMap<>();
        statusInput.put("distinctEpisodesWatched", distinctWatched);
        metadata.map(ShowMetadata::totalEpisodes).ifPresent(total -> statusInput.put("totalEpisodes", total));
        metadata.map(ShowMetadata::productionEnded).ifPresent(ended -> statusInput.put("productionEnded", ended));
        diagnostics.success(
                DiagnosticsStage.PROGRESS_BUILDER,
                show.title(),
                "Completion status",
                statusInput,
                java.util.Map.of("status", status));

        return Optional.of(finalProgress);
    }

    /**
     * Determines completion status by comparing how many distinct episodes have been watched
     * against a provider's total episode count, when one is known. See {@link CompletionStatus}
     * for why this is a mechanical judgment, deliberately independent of the user's own
     * WatchStatus.
     *
     * <p>Distinct episode count is used rather than "highest position reached" because someone who
     * watches all of season 1 and then skips straight to season 3 episode 1 has a high position
     * but has only watched a fraction of the show; counting distinct positions catches that, a
     * running max would not.
     *
     * <p>A show that is fully caught up but still airing new episodes is deliberately reported as
     * IN_PROGRESS, not COMPLETED: more of the story is still to come, so calling it complete would
     * be a false claim even though every currently-available episode has been watched.
     */
    private static CompletionStatus completionStatus(int distinctWatched, Optional<ShowMetadata> metadata) {
        Integer total = metadata.map(ShowMetadata::totalEpisodes).orElse(null);
        if (total == null || total <= 0) {
            return CompletionStatus.UNKNOWN;
        }
        if (distinctWatched < total) {
            return CompletionStatus.IN_PROGRESS;
        }
        Boolean ended = metadata.map(ShowMetadata::productionEnded).orElse(null);
        return Boolean.TRUE.equals(ended) ? CompletionStatus.COMPLETED : CompletionStatus.IN_PROGRESS;
    }

    /**
     * Resolves one named episode title to a provider position.
     *
     * <p>When metadata already identified the series, resolution uses only that provider id:
     * season-scoped first (when Netflix supplied a season/container number), then any-season on
     * the same id. Netflix "Part" / container numbers often disagree with provider seasons, so
     * scoped-only resolution would leave real matches as null. Title-based any-season is not used
     * after an id attempt — that would re-crawl the same guide (residual-C cold-cache regression).
     *
     * <p>When no metadata is available, a single cleaned-title lookup is used (scoped then
     * any-season).
     */
    private Optional<WatchProgress> resolveNamedEpisode(
            Show show, NamedEpisode named, ShowType progType, Optional<ShowMetadata> metadata) {
        java.util.Map<String, Object> searched = new java.util.LinkedHashMap<>();
        if (named.season() != null) {
            searched.put("season", named.season());
        } else {
            searched.put("season", "any");
        }
        searched.put("episodeTitle", named.episodeTitle());

        Optional<WatchProgress> resolved = Optional.empty();
        String resolutionPath = null;

        if (metadata.isPresent()) {
            long providerId = metadata.get().providerId();
            if (named.season() != null) {
                resolved = resolveEpisodeSafely(providerId, named.season(), named.episodeTitle());
            }
            if (resolved.isEmpty()) {
                // Same series, full guide — in-memory season cache reuses any season already fetched.
                resolved = resolveEpisodeAnySeasonSafely(providerId, named.episodeTitle());
            }
            if (resolved.isPresent()) {
                resolutionPath = "providerId";
            }
        } else {
            String lookupTitle = lookupTitleForResolution(show.title(), metadata);
            searched.put("lookupTitle", lookupTitle);
            if (named.season() != null) {
                resolved = resolveEpisodeSafely(lookupTitle, named.season(), named.episodeTitle());
            }
            if (resolved.isEmpty()) {
                resolved = resolveEpisodeAnySeasonSafely(lookupTitle, named.episodeTitle());
            }
            if (resolved.isPresent()) {
                resolutionPath = "lookupTitle";
            }
        }

        if (resolved.isPresent()) {
            WatchProgress wp = resolved.get();
            // wp.status() is still CompletionStatus.UNKNOWN here too: the provider
            // only resolved a season/episode position, not a total to compare it
            // against. Only the type is corrected to the show's own resolved type.
            WatchProgress updated =
                    new WatchProgress(progType, wp.season(), wp.episode(), wp.provider(), wp.status());
            java.util.Map<String, Object> output = new java.util.LinkedHashMap<>();
            output.put("season", updated.season());
            output.put("episode", updated.episode());
            output.put("provider", updated.provider());
            if (resolutionPath != null) {
                output.put("resolutionPath", resolutionPath);
            }
            diagnostics.success(
                    DiagnosticsStage.EPISODE_RESOLVER, show.title(), "Episode title resolved", searched, output);
            return Optional.of(updated);
        }
        diagnostics.failure(DiagnosticsStage.EPISODE_RESOLVER, show.title(), "Episode title not found", searched);
        return Optional.empty();
    }

    /** Resolves an episode by title; provider failures are treated as misses. */
    private Optional<WatchProgress> resolveEpisodeSafely(String showTitle, int season, String episodeTitle) {
        try {
            return provider.resolveEpisode(showTitle, season, episodeTitle);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Resolves an episode by provider id; provider failures are treated as misses. */
    private Optional<WatchProgress> resolveEpisodeSafely(long providerId, int season, String episodeTitle) {
        try {
            return provider.resolveEpisode(providerId, season, episodeTitle);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Resolves an episode any-season by title; provider failures are treated as misses. */
    private Optional<WatchProgress> resolveEpisodeAnySeasonSafely(String showTitle, String episodeTitle) {
        try {
            return provider.resolveEpisodeAnySeason(showTitle, episodeTitle);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Resolves an episode any-season by provider id; provider failures are treated as misses. */
    private Optional<WatchProgress> resolveEpisodeAnySeasonSafely(long providerId, String episodeTitle) {
        try {
            return provider.resolveEpisodeAnySeason(providerId, episodeTitle);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Associates the imported title, a stripped Limited Series variant, and the provider's own
     * title with the matched series id for the remainder of the run.
     */
    private void seedSeriesMappings(String originalTitle, ShowMetadata metadata) {
        long id = metadata.providerId();
        provider.rememberSeriesMapping(originalTitle, id);
        String stripped = stripLimitedSeries(originalTitle);
        if (stripped != null && !stripped.isBlank() && !stripped.equals(originalTitle)) {
            provider.rememberSeriesMapping(stripped, id);
        }
        if (metadata.title() != null && !metadata.title().isBlank()) {
            provider.rememberSeriesMapping(metadata.title(), id);
        }
    }

    /**
     * Chooses the title used for title-based episode resolution: prefer the provider's canonical
     * title from a prior metadata match, otherwise strip a trailing {@code Limited Series} label
     * from the imported show title.
     */
    private static String lookupTitleForResolution(String showTitle, Optional<ShowMetadata> metadata) {
        if (metadata.isPresent()) {
            String providerTitle = metadata.get().title();
            if (providerTitle != null && !providerTitle.isBlank()) {
                return providerTitle.trim();
            }
        }
        String cleaned = stripLimitedSeries(showTitle);
        return cleaned == null || cleaned.isBlank() ? showTitle : cleaned;
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

    /**
     * A distinct (season, episode) position, used to count distinct watched episodes for
     * completion status regardless of whether they were Netflix-numbered or resolved from a named
     * episode title.
     */
    private record EpisodeKey(int season, int episode) {}
}
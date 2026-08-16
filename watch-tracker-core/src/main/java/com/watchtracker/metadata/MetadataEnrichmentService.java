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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Enriches already-grouped shows without changing raw import data. */
public final class MetadataEnrichmentService {
    private final MetadataProvider provider;
    private final DiagnosticsCollector diagnostics;
    private final MetadataOverrideStore overrides;
    private final RevokedOverrideStore revoked;
    private final ProgressResolver progressResolver;
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
        this(provider, diagnostics, overrides, RevokedOverrideStore.EMPTY);
    }

    /**
     * Creates a service with manual overrides and an optional revoked-override list.
     *
     * @param provider provider used to retrieve metadata
     * @param diagnostics diagnostics collector for recording resolution steps
     * @param overrides human-confirmed corrections consulted before any search
     * @param revoked titles withdrawn from sticky MANUAL_OVERRIDE treatment
     */
    public MetadataEnrichmentService(
            MetadataProvider provider,
            DiagnosticsCollector diagnostics,
            MetadataOverrideStore overrides,
            RevokedOverrideStore revoked) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
        this.overrides = Objects.requireNonNull(overrides, "overrides must not be null");
        this.revoked = Objects.requireNonNull(revoked, "revoked must not be null");
        this.progressResolver = new ProgressResolver(this.provider, this.diagnostics);
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
     * <p>Prior metadata with {@link MatchConfidence#MANUAL_OVERRIDE} is sticky: it is reused even
     * if the show aggregate id changed (title/alias match against the prior export) or watch data
     * shifted, and progress is resolved again from the current events. That is what keeps a
     * human-confirmed match (e.g. Lady's Companion via overrides.json) from falling back to
     * {@code NO_METADATA_MATCH} after the override is removed and episodes re-split under new
     * titles. Automated matches are not sticky — they only fully reuse when the show is unchanged
     * under the same id. Missing progress is always re-attempted when metadata is available and
     * the prior row is otherwise eligible for reuse.
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
        Optional<ShowMetadata> overrideMetadata = resolveOverride(show);
        if (overrideMetadata.isPresent()) {
            ShowMetadata md = overrideMetadata.get();
            diagnostics.success(
                    DiagnosticsStage.METADATA_PROVIDER,
                    show.title(),
                    "Matched metadata from manual override",
                    null,
                    java.util.Map.of("provider", md.provider(), "id", md.providerId()));
        }

        // When an override is configured for this show it wins outright — prior results are
        // ignored so a human correction can never be shadowed by a stale shows.json entry.
        // Otherwise consult the previous export. Id match is preferred; title/alias matching is
        // only used to recover prior rows whose aggregate id shifted (e.g. override removed and
        // episodes re-split). Sticky reuse across id/data changes is restricted to metadata that
        // was produced by a manual override ({@link MatchConfidence#MANUAL_OVERRIDE}) — automated
        // matches are not sticky and re-resolve when the show is no longer unchanged.
        Optional<EnrichedShow> priorOpt =
                overrideMetadata.isPresent() ? Optional.empty() : previous.findMatching(show);
        if (priorOpt.isPresent()) {
            EnrichedShow prior = priorOpt.get();
            boolean sameId = prior.show().id().equals(show.id());
            boolean unchanged = unchangedSincePrevious(show, prior.show());
            boolean manualOverride = isManualOverrideMetadata(prior.metadata()) && !isRevoked(show);

            if (unchanged && sameId) {
                // Nothing about the imported show changed. Prefer reusing prior work, but never
                // leave progress unresolved when metadata is available — an earlier run may have
                // matched the title and still failed episode resolution (or predated a progress fix).
                if (prior.metadata().isEmpty() && prior.progress().isEmpty()) {
                    diagnostics.info(
                            DiagnosticsStage.METADATA_PROVIDER,
                            show.title(),
                            "Previous snapshot present and unchanged but missing metadata and progress; resolving fresh");
                } else if (prior.progress().isEmpty()) {
                    return reuseMetadataResolveProgress(
                            show,
                            events,
                            prior,
                            "Reused cached metadata; re-resolving progress (previous run had none)");
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

                    // prior.progress().type() is the fully-resolved type from whenever this show
                    // was last freshly resolved — already accounting for the documentary/
                    // limited-series adjustments a fresh resolution applies — so reuse it directly
                    // rather than re-deriving it here, keeping the two paths in sync by
                    // construction instead of by keeping duplicated derivation logic consistent.
                    Show finalShow = withType(show, wp.type());
                    return new EnrichedShow(finalShow, prior.metadata(), prior.progress());
                }
            } else if (manualOverride) {
                // Sticky only for human-confirmed overrides. After the override is removed from
                // overrides.json, the shows.json row still carries matchConfidence=MANUAL_OVERRIDE;
                // title/alias matching recovers it even when the aggregate id changed because
                // episode titles are no longer consolidated. Automated matches are intentionally
                // not sticky — they re-search when watch data or grouping changes.
                String reason = sameId
                        ? "Reused MANUAL_OVERRIDE metadata after show data changed; re-resolving progress"
                        : "Reused MANUAL_OVERRIDE metadata via title/alias match (show id changed, e.g. override removed); re-resolving progress";
                diagnostics.info(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        sameId
                                ? "Previous MANUAL_OVERRIDE metadata present but show data changed; keeping it"
                                : "Previous MANUAL_OVERRIDE metadata matched by title/alias (id differs); keeping it");
                return reuseMetadataResolveProgress(show, events, prior, reason);
            } else {
                // Automated prior match, but show is no longer unchanged (or id shifted without a
                // manual override) — resolve fresh.
                diagnostics.info(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
                        sameId
                                ? "Previous snapshot present but show data changed; resolving fresh"
                                : "Previous snapshot matched by title/alias but was not MANUAL_OVERRIDE; resolving fresh");
                if (sameId) {
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
            progressResolver.seedSeriesMappings(originalTitle, md);
            mdType = resolveShowType(show, md);
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

        // Always attempt progress resolution. When metadata is present (override, cache, or
        // fresh match), episode resolution can use provider guides; when it is absent, numbered
        // Netflix positions are still applied so progress is not dropped solely because title
        // search failed.
        metadata = demoteIfRevoked(show, metadata);
        ShowType progressType = metadata.isPresent() ? progressTypeFor(mdType, metadata.get()) : mdType;
        Optional<WatchProgress> progress = progressResolver.resolve(show, events, progressType, metadata);
        Show finalShow = metadata.isPresent() ? withType(show, mdType) : show;
        return new EnrichedShow(finalShow, metadata, progress);

    }




    private boolean isRevoked(Show show) {
        return revoked.isRevoked(show.title(), show.aliases());
    }

    /**
     * Demotes MANUAL_OVERRIDE when the title is on the revoked list so the show reappears as a
     * low-confidence review item instead of staying sticky-confirmed.
     */
    private Optional<ShowMetadata> demoteIfRevoked(Show show, Optional<ShowMetadata> metadata) {
        if (metadata.isEmpty() || !isRevoked(show)) {
            return metadata;
        }
        ShowMetadata md = metadata.get();
        if (md.matchConfidence() != MatchConfidence.MANUAL_OVERRIDE) {
            return metadata;
        }
        diagnostics.info(
                DiagnosticsStage.METADATA_PROVIDER,
                show.title(),
                "MANUAL_OVERRIDE demoted because title is on revoked-overrides list");
        return Optional.of(md.withMatchConfidence(MatchConfidence.FIRST_RESULT_FALLBACK));
    }

    /** True when prior metadata was produced by a human override ({@link MatchConfidence#MANUAL_OVERRIDE}). */
    private static boolean isManualOverrideMetadata(Optional<ShowMetadata> metadata) {
        return metadata.isPresent()
                && metadata.get().matchConfidence() == MatchConfidence.MANUAL_OVERRIDE;
    }

    /**
     * Keeps prior metadata, seeds series mappings, and always re-resolves progress from the
     * current events. Used whenever a previous export already identified the provider entity but
     * progress is missing, stale, or the aggregate id/title shifted (override removed).
     */
    private EnrichedShow reuseMetadataResolveProgress(
            Show show, List<WatchEvent> events, EnrichedShow prior, String metadataMessage) {
        Optional<ShowMetadata> meta = demoteIfRevoked(show, prior.metadata());
        ShowMetadata md = meta.get();
        diagnostics.success(
                DiagnosticsStage.METADATA_PROVIDER,
                show.title(),
                metadataMessage,
                null,
                java.util.Map.of("provider", md.provider(), "id", md.providerId()));
        progressResolver.seedSeriesMappings(show.title(), md);
        ShowType mdType = resolveShowType(show, md);
        ShowType progressType = progressTypeFor(mdType, md);
        Optional<WatchProgress> progress = progressResolver.resolve(show, events, progressType, meta);
        return new EnrichedShow(withType(show, mdType), meta, progress);
    }

    /**
     * Derives the show type used for export and progress from provider metadata, applying the
     * same documentary / limited-series adjustments on every path (prior reuse, override, fresh).
     */
    private static ShowType resolveShowType(Show show, ShowMetadata md) {
        ShowType mdType = md.type();
        boolean hasLimited = show.title().toLowerCase(Locale.ROOT).contains("limited series")
                || show.aliases().stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).contains("limited series"));
        boolean hasDocumentaryGenre = md.genres().stream()
                .filter(g -> g != null)
                .anyMatch(g -> g.toLowerCase(Locale.ROOT).contains("documentary"));
        if (hasDocumentaryGenre) {
            return ShowType.DOCUMENTARY;
        }
        if (mdType == ShowType.SERIES && hasLimited) {
            return ShowType.LIMITED_SERIES;
        }
        return mdType;
    }

    /**
     * Documentaries that the provider catalogued as movies are progressed as movies (no season /
     * episode); every other type uses the resolved show type as-is.
     */
    private static ShowType progressTypeFor(ShowType mdType, ShowMetadata md) {
        if (mdType == ShowType.DOCUMENTARY && md.type() == ShowType.MOVIE) {
            return ShowType.MOVIE;
        }
        return mdType;
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
     * Resolves a manual override for a show, if one is configured, bypassing title search entirely
     * in favor of the human-confirmed provider entity. Matching uses the show's canonical title and
     * raw aliases — see {@link MetadataOverrideStore#forShow}.
     *
     * <p>A configured-but-unresolvable override (a typo'd id, a since-removed entry, a transient
     * provider failure) is treated as absent rather than propagated as an error: this show simply
     * falls through to the normal resolution path, the same as if no override existed, rather than
     * the whole import aborting over one bad correction.
     */
    private Optional<ShowMetadata> resolveOverride(Show show) {
        Optional<MetadataOverride> override = overrides.forShow(show.title(), show.aliases());
        if (override.isEmpty()) {
            return Optional.empty();
        }
        MetadataOverride o = override.get();
        try {
            Optional<ShowMetadata> resolved = provider.findById(o.provider(), o.mediaType(), o.providerId());
            if (resolved.isEmpty()) {
                // Offline / cold id-cache: reuse a title-cache hit only when providerId matches the
                // override, then stamp MANUAL_OVERRIDE so sticky/override_data stay correct.
                resolved = findCachedOverrideMetadata(show, o);
            }
            if (resolved.isEmpty()) {
                diagnostics.failure(
                        DiagnosticsStage.METADATA_PROVIDER,
                        show.title(),
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
                    show.title(),
                    "Manual override lookup failed: " + exception.getMessage() + "; falling back to normal resolution");
            return Optional.empty();
        }
    }

    /**
     * When {@code findById} misses (offline and id-cache not yet populated), accept a title-keyed
     * cache entry only if it is the same provider entity the human overrode.
     */
    private Optional<ShowMetadata> findCachedOverrideMetadata(Show show, MetadataOverride override) {
        if (!(provider instanceof CachingMetadataProvider caching)) {
            return Optional.empty();
        }
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>();
        if (show.title() != null && !show.title().isBlank()) {
            keys.add(show.title().trim());
        }
        if (show.aliases() != null) {
            for (String alias : show.aliases()) {
                if (alias != null && !alias.isBlank()) {
                    keys.add(alias.trim());
                }
            }
        }
        if (override.title() != null && !override.title().isBlank()) {
            keys.add(override.title().trim());
        }
        for (String key : keys) {
            Optional<ShowMetadata> cached = caching.getFromCache(key);
            if (cached.isEmpty()) {
                continue;
            }
            ShowMetadata md = cached.get();
            if (md.providerId() != override.providerId()) {
                continue;
            }
            if (override.provider() != null
                    && md.provider() != null
                    && !override.provider().equalsIgnoreCase(md.provider())) {
                continue;
            }
            diagnostics.info(
                    DiagnosticsStage.METADATA_PROVIDER,
                    show.title(),
                    "Manual override satisfied from title cache (id=" + override.providerId() + ")");
            ShowMetadata stamped = md.matchConfidence() == MatchConfidence.MANUAL_OVERRIDE
                    ? md
                    : md.withMatchConfidence(MatchConfidence.MANUAL_OVERRIDE);
            caching.putById(override.provider(), override.mediaType(), override.providerId(), stamped);
            return Optional.of(stamped);
        }
        return Optional.empty();
    }

    /** True when nothing that could affect provider resolution has changed since the previous run. */
    private static boolean unchangedSincePrevious(Show current, Show previous) {
        return current.watchCount() == previous.watchCount()
                && Objects.equals(current.highestSeasonSeen(), previous.highestSeasonSeen())
                && Objects.equals(current.highestEpisodeSeen(), previous.highestEpisodeSeen())
                && current.aliases().equals(previous.aliases());
    }

}

package com.watchtracker.metadata;

import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.DiagnosticsStage;
import com.watchtracker.model.CompletionStatus;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import com.watchtracker.model.WatchEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the highest watched episode position for a show from Netflix-numbered events and
 * provider-named episode lookups. Extracted from {@link MetadataEnrichmentService} so progress
 * logic can evolve independently of metadata search and prior-reuse policy.
 */
public final class ProgressResolver {
    private static final Pattern LIMITED_SERIES_TRAILING =
            Pattern.compile("(?i)\s*:?\s*limited series\b\s*$");

    private final MetadataProvider provider;
    private final DiagnosticsCollector diagnostics;

    public ProgressResolver(MetadataProvider provider, DiagnosticsCollector diagnostics) {
        this.provider = Objects.requireNonNull(provider, "provider must not be null");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    Optional<WatchProgress> resolve(Show show, List<WatchEvent> events, ShowType resolvedType, Optional<ShowMetadata> metadata) {
        if (resolvedType == ShowType.MOVIE) {
            List<WatchEvent> showEvents = events.stream()
                    .filter(event -> belongsToShow(show, event))
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
                .filter(event -> belongsToShow(show, event))
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
     * Determines if an event belongs to a show, accounting for show consolidation via manual overrides.
     * An event belongs to a show if:
     * 1. The show title equals the event's canonical show (original behavior)
     * 2. The event's canonical show starts with the show title + ": " (consolidated episodes)
     * 3. The event's raw title is in the show's aliases (consolidated by override)
     */
    boolean belongsToShow(Show show, WatchEvent event) {
        // Original behavior: exact match on canonical show
        if (show.title().equals(event.canonicalShow())) {
            return true;
        }
        
        // Consolidated episodes: event's canonical show is "Show Title: Episode X"
        // and show title is the base "Show Title"
        if (event.canonicalShow() != null && event.canonicalShow().startsWith(show.title() + ": ")) {
            return true;
        }
        
        // Consolidated by override: event's raw title is in show's aliases
        if (show.aliases().contains(event.rawTitle())) {
            return true;
        }
        
        return false;
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
    static CompletionStatus completionStatus(int distinctWatched, Optional<ShowMetadata> metadata) {
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
     *
     * <p>If every attempt with the full episode title fails, and the title ends in a trailing
     * ", Part N" / ": Part N" split-viewing marker (Netflix sometimes records a single provider
     * episode as two watched "parts", e.g. "Free Radicals, Part 2", when the provider's own guide
     * has no such split — see {@code TitleNormalizer}'s {@code CONTAINER_TITLE} pattern, which
     * keeps this suffix as part of the extracted episode title verbatim), the same scoped/any-season
     * attempts are retried once more with that suffix stripped. This is a strict superset of the
     * unstripped attempts — it only ever recovers a match the full-title search already missed, so a
     * title without this suffix, or one that already resolved on the full title, is completely
     * unaffected.
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
            } else {
                String stripped = stripTrailingPart(named.episodeTitle());
                if (stripped != null) {
                    searched.put("strippedEpisodeTitle", stripped);
                    if (named.season() != null) {
                        resolved = resolveEpisodeSafely(providerId, named.season(), stripped);
                    }
                    if (resolved.isEmpty()) {
                        resolved = resolveEpisodeAnySeasonSafely(providerId, stripped);
                    }
                    if (resolved.isPresent()) {
                        resolutionPath = "providerId-strippedPart";
                    }
                }
                if (resolved.isEmpty()) {
                    String parenthesized = withParenthesizedPartNumber(named.episodeTitle());
                    if (parenthesized != null) {
                        searched.put("parenthesizedEpisodeTitle", parenthesized);
                        if (named.season() != null) {
                            resolved = resolveEpisodeSafely(providerId, named.season(), parenthesized);
                        }
                        if (resolved.isEmpty()) {
                            resolved = resolveEpisodeAnySeasonSafely(providerId, parenthesized);
                        }
                        if (resolved.isPresent()) {
                            resolutionPath = "providerId-parenthesizedPart";
                        }
                    }
                }
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
            } else {
                String stripped = stripTrailingPart(named.episodeTitle());
                if (stripped != null) {
                    searched.put("strippedEpisodeTitle", stripped);
                    if (named.season() != null) {
                        resolved = resolveEpisodeSafely(lookupTitle, named.season(), stripped);
                    }
                    if (resolved.isEmpty()) {
                        resolved = resolveEpisodeAnySeasonSafely(lookupTitle, stripped);
                    }
                    if (resolved.isPresent()) {
                        resolutionPath = "lookupTitle-strippedPart";
                    }
                }
                if (resolved.isEmpty()) {
                    String parenthesized = withParenthesizedPartNumber(named.episodeTitle());
                    if (parenthesized != null) {
                        searched.put("parenthesizedEpisodeTitle", parenthesized);
                        if (named.season() != null) {
                            resolved = resolveEpisodeSafely(lookupTitle, named.season(), parenthesized);
                        }
                        if (resolved.isEmpty()) {
                            resolved = resolveEpisodeAnySeasonSafely(lookupTitle, parenthesized);
                        }
                        if (resolved.isPresent()) {
                            resolutionPath = "lookupTitle-parenthesizedPart";
                        }
                    }
                }
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

    // Matches a trailing ", Part N" / ": Part N" / " Part N" split-viewing marker — digits or a
    // spelled-out number up to twenty, covering the realistic range for how many parts Netflix
    // would split a single episode's viewing into. Deliberately narrow (only a trailing "Part N",
    // not "Chapter"/"Book"/etc., which TitleNormalizer's own patterns already treat as meaningful
    // season/container markers rather than noise) so this can't strip something that was actually
    // part of a real episode title.
    private static final Pattern TRAILING_PART_SUFFIX = Pattern.compile(
            "(?i)^(?<base>.+?)\\s*[,:]?\\s*part\\s+(?<number>[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten"
                    + "|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\\s*$");

    private static final java.util.Map<String, Integer> SPELLED_OUT_NUMBERS = java.util.Map.ofEntries(
            java.util.Map.entry("one", 1),
            java.util.Map.entry("two", 2),
            java.util.Map.entry("three", 3),
            java.util.Map.entry("four", 4),
            java.util.Map.entry("five", 5),
            java.util.Map.entry("six", 6),
            java.util.Map.entry("seven", 7),
            java.util.Map.entry("eight", 8),
            java.util.Map.entry("nine", 9),
            java.util.Map.entry("ten", 10),
            java.util.Map.entry("eleven", 11),
            java.util.Map.entry("twelve", 12),
            java.util.Map.entry("thirteen", 13),
            java.util.Map.entry("fourteen", 14),
            java.util.Map.entry("fifteen", 15),
            java.util.Map.entry("sixteen", 16),
            java.util.Map.entry("seventeen", 17),
            java.util.Map.entry("eighteen", 18),
            java.util.Map.entry("nineteen", 19),
            java.util.Map.entry("twenty", 20));

    /**
     * Strips a trailing ", Part N" (or similar) suffix from an episode title, when present.
     *
     * @param episodeTitle the raw episode title
     * @return the title with the suffix removed, or {@code null} if there was no such suffix (the
     *     caller should not retry resolution in that case — the full-title attempt already covered
     *     it)
     */
    private static String stripTrailingPart(String episodeTitle) {
        if (episodeTitle == null) {
            return null;
        }
        Matcher matcher = TRAILING_PART_SUFFIX.matcher(episodeTitle.trim());
        return matcher.matches() ? matcher.group("base").trim() : null;
    }

    /**
     * Rewrites a trailing ", Part N" suffix into {@code "Base (N)"}, when present — the convention
     * TMDb actually uses for a multi-part episode (e.g. "Hell Week (1)", "Hell Week (2)"), confirmed
     * against a real title page. This is a distinct fallback from {@link #stripTrailingPart}: a
     * plain stripped title with no part number at all ("Hell Week") also fails to match, since
     * TMDb's own episode name still carries the number, just in parentheses rather than after the
     * word "Part".
     *
     * @param episodeTitle the raw episode title
     * @return the reformatted title, or {@code null} if there was no "Part N" suffix to rewrite, or
     *     the captured number couldn't be parsed
     */
    private static String withParenthesizedPartNumber(String episodeTitle) {
        if (episodeTitle == null) {
            return null;
        }
        Matcher matcher = TRAILING_PART_SUFFIX.matcher(episodeTitle.trim());
        if (!matcher.matches()) {
            return null;
        }
        String token = matcher.group("number").toLowerCase(Locale.ROOT);
        Integer number = SPELLED_OUT_NUMBERS.get(token);
        if (number == null) {
            try {
                number = Integer.parseInt(token);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return matcher.group("base").trim() + " (" + number + ")";
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
    void seedSeriesMappings(String originalTitle, ShowMetadata metadata) {
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
    static String lookupTitleForResolution(String showTitle, Optional<ShowMetadata> metadata) {
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
    static Set<NamedEpisode> uniqueNamedEpisodes(List<WatchEvent> showEvents) {
        Set<NamedEpisode> unique = new LinkedHashSet<>();
        for (WatchEvent event : showEvents) {
            if (event.episode() == null && event.episodeTitle() != null) {
                unique.add(new NamedEpisode(event.season(), event.episodeTitle()));
            }
        }
        return unique;
    }

    /** A distinct named-episode-title pair awaiting provider resolution, with an optional season. */
    record NamedEpisode(Integer season, String episodeTitle) {}

    /**
     * A distinct (season, episode) position, used to count distinct watched episodes for
     * completion status regardless of whether they were Netflix-numbered or resolved from a named
     * episode title.
     */
    record EpisodeKey(int season, int episode) {}
    private static String stripLimitedSeries(String title) {
        if (title == null) {
            return null;
        }
        return LIMITED_SERIES_TRAILING.matcher(title).replaceAll("").trim();
    }
}

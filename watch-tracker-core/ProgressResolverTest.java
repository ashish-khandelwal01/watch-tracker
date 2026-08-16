package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.model.CompletionStatus;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import com.watchtracker.model.WatchEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProgressResolver}, isolated from {@link MetadataEnrichmentService}'s
 * override/prior-snapshot policy. Coverage here focuses on: movie vs series progress, distinct
 * -episode completion counting, show/event association (including override-consolidated
 * aliases), named-episode resolution fallbacks (id vs title, scoped vs any-season, "Part N"
 * stripping/parenthesizing), and series-mapping seeding.
 */
class ProgressResolverTest {

    private static Show show(UUID id, String title, Set<String> aliases, int watchCount) {
        return new Show(
                id,
                title,
                aliases,
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                null,
                null,
                watchCount);
    }

    private static WatchEvent numbered(String canonicalShow, int season, int episode) {
        return new WatchEvent(
                UUID.randomUUID(),
                canonicalShow + " Ep",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "NETFLIX",
                canonicalShow,
                season,
                episode,
                null,
                true);
    }

    private static WatchEvent named(String canonicalShow, Integer season, String episodeTitle) {
        return new WatchEvent(
                UUID.randomUUID(),
                canonicalShow + ": " + episodeTitle,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "NETFLIX",
                canonicalShow,
                season,
                null,
                episodeTitle,
                true);
    }

    private static ShowMetadata metadata(long id, String title, ShowType type) {
        return new ShowMetadata("TMDB", id, title, type, List.of(), null, null, null);
    }

    // --- Movie progress ---------------------------------------------------

    @Test
    void movieWithBelongingEventResolvesToCompleted() {
        UUID id = UUID.randomUUID();
        Show show = show(id, "Arrival", Set.of(), 1);
        WatchEvent movieEvent = new WatchEvent(
                UUID.randomUUID(), "Arrival", LocalDateTime.now(), "NETFLIX", "Arrival", null, null, null, true);
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress =
                resolver.resolve(show, List.of(movieEvent), ShowType.MOVIE, Optional.empty());

        assertThat(progress).isPresent();
        assertThat(progress.get().type()).isEqualTo(ShowType.MOVIE);
        assertThat(progress.get().season()).isNull();
        assertThat(progress.get().episode()).isNull();
        assertThat(progress.get().status()).isEqualTo(CompletionStatus.COMPLETED);
        assertThat(progress.get().provider()).isEqualTo("NETFLIX");
    }

    @Test
    void movieUsesMetadataProviderNameWhenPresent() {
        Show show = show(UUID.randomUUID(), "Arrival", Set.of(), 1);
        WatchEvent movieEvent = new WatchEvent(
                UUID.randomUUID(), "Arrival", LocalDateTime.now(), "NETFLIX", "Arrival", null, null, null, true);
        ShowMetadata md = metadata(1, "Arrival", ShowType.MOVIE);
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress =
                resolver.resolve(show, List.of(movieEvent), ShowType.MOVIE, Optional.of(md));

        assertThat(progress).hasValueSatisfying(p -> assertThat(p.provider()).isEqualTo("TMDB"));
    }

    @Test
    void movieWithNoBelongingEventResolvesToEmpty() {
        Show show = show(UUID.randomUUID(), "Arrival", Set.of(), 1);
        WatchEvent unrelated = new WatchEvent(
                UUID.randomUUID(), "Other", LocalDateTime.now(), "NETFLIX", "Other Movie", null, null, null, true);
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress =
                resolver.resolve(show, List.of(unrelated), ShowType.MOVIE, Optional.empty());

        assertThat(progress).isEmpty();
    }

    // --- Series: highest numbered position ---------------------------------

    @Test
    void seriesTakesHighestNumberedPositionAcrossSeasons() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 3);
        List<WatchEvent> events =
                List.of(numbered("Dark", 1, 5), numbered("Dark", 2, 1), numbered("Dark", 1, 8));
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.empty());

        assertThat(progress).hasValueSatisfying(p -> {
            assertThat(p.season()).isEqualTo(2);
            assertThat(p.episode()).isEqualTo(1);
        });
    }

    @Test
    void seriesWithNoBelongingEventsResolvesToEmpty() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(numbered("Other Show", 1, 1));
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        assertThat(resolver.resolve(show, events, ShowType.SERIES, Optional.empty())).isEmpty();
    }

    @Test
    void nullOrUnknownResolvedTypeDefaultsProgressTypeToSeries() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(numbered("Dark", 1, 1));
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.UNKNOWN, Optional.empty());

        assertThat(progress).hasValueSatisfying(p -> assertThat(p.type()).isEqualTo(ShowType.SERIES));
    }

    // --- Completion status: distinct-episode counting -----------------------

    @Test
    void distinctEpisodeCountCatchesSkippedSeasonsUnlikeRunningMax() {
        // Watched all of season 1 (2 eps) then jumped to season 3 episode 1: high position (S3E1)
        // but only 3 of 10 total episodes actually watched -> must stay IN_PROGRESS, not be
        // miscounted as complete purely from the max position reached.
        Show show = show(UUID.randomUUID(), "Skipper", Set.of(), 3);
        List<WatchEvent> events =
                List.of(numbered("Skipper", 1, 1), numbered("Skipper", 1, 2), numbered("Skipper", 3, 1));
        ShowMetadata md = new ShowMetadata(
                "TMDB", 1, "Skipper", ShowType.SERIES, List.of(), null, null, null, 10, true);
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.of(md));

        assertThat(progress).hasValueSatisfying(p -> {
            assertThat(p.season()).isEqualTo(3);
            assertThat(p.episode()).isEqualTo(1);
            assertThat(p.status()).isEqualTo(CompletionStatus.IN_PROGRESS);
        });
    }

    @Test
    void completionStatusUnknownWhenNoTotalEpisodes() {
        assertThat(ProgressResolver.completionStatus(5, Optional.empty())).isEqualTo(CompletionStatus.UNKNOWN);
    }

    @Test
    void completionStatusUnknownWhenTotalEpisodesIsZeroOrNegative() {
        ShowMetadata zero = new ShowMetadata("TMDB", 1, "X", ShowType.SERIES, List.of(), null, null, null, 0, true);
        assertThat(ProgressResolver.completionStatus(0, Optional.of(zero))).isEqualTo(CompletionStatus.UNKNOWN);
    }

    @Test
    void completionStatusInProgressWhenBelowTotal() {
        ShowMetadata md = new ShowMetadata("TMDB", 1, "X", ShowType.SERIES, List.of(), null, null, null, 10, true);
        assertThat(ProgressResolver.completionStatus(4, Optional.of(md))).isEqualTo(CompletionStatus.IN_PROGRESS);
    }

    @Test
    void completionStatusCompletedWhenAtTotalAndProductionEnded() {
        ShowMetadata md = new ShowMetadata("TMDB", 1, "X", ShowType.SERIES, List.of(), null, null, null, 10, true);
        assertThat(ProgressResolver.completionStatus(10, Optional.of(md))).isEqualTo(CompletionStatus.COMPLETED);
    }

    @Test
    void completionStatusStaysInProgressWhenAtTotalButStillAiring() {
        // Caught up on every currently-available episode, but the show has not finished its run:
        // this must not be reported as COMPLETED (see WatchProgress/CompletionStatus docs).
        ShowMetadata md = new ShowMetadata("TMDB", 1, "X", ShowType.SERIES, List.of(), null, null, null, 10, false);
        assertThat(ProgressResolver.completionStatus(10, Optional.of(md))).isEqualTo(CompletionStatus.IN_PROGRESS);
    }

    @Test
    void completionStatusInProgressWhenAtTotalButProductionEndedUnknown() {
        ShowMetadata md = new ShowMetadata("TMDB", 1, "X", ShowType.SERIES, List.of(), null, null, null, 10, null);
        assertThat(ProgressResolver.completionStatus(10, Optional.of(md))).isEqualTo(CompletionStatus.IN_PROGRESS);
    }

    // --- belongsToShow -------------------------------------------------------

    @Test
    void belongsToShowMatchesExactCanonicalTitle() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        WatchEvent event = numbered("Dark", 1, 1);
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        assertThat(resolver.belongsToShow(show, event)).isTrue();
    }

    @Test
    void belongsToShowMatchesConsolidatedEpisodePrefix() {
        Show show = show(UUID.randomUUID(), "Love to Hate You", Set.of(), 1);
        WatchEvent event = numbered("Love to Hate You: Femme Fatale vs Homme Fatale", 1, 1);
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        assertThat(resolver.belongsToShow(show, event)).isTrue();
    }

    @Test
    void belongsToShowMatchesRawTitleAliasFromOverrideConsolidation() {
        Show show = show(UUID.randomUUID(), "Kingsman", Set.of("Kingsman: The Golden Circle"), 1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Kingsman: The Golden Circle",
                LocalDateTime.now(),
                "NETFLIX",
                "Golden Circle", // canonical title diverges from show title; only alias matches
                null,
                null,
                null,
                true);
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        assertThat(resolver.belongsToShow(show, event)).isTrue();
    }

    @Test
    void belongsToShowRejectsUnrelatedEvent() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        WatchEvent event = numbered("Stranger Things", 1, 1);
        ProgressResolver resolver = new ProgressResolver(new StubProvider(), new NoOpDiagnosticsCollector());

        assertThat(resolver.belongsToShow(show, event)).isFalse();
    }

    // --- uniqueNamedEpisodes ---------------------------------------------------

    @Test
    void uniqueNamedEpisodesCollapsesDuplicateSeasonTitlePairs() {
        List<WatchEvent> events = List.of(
                named("Dark", 1, "Secrets"),
                named("Dark", 1, "Secrets"), // duplicate, same season+title
                named("Dark", 1, "Lies"));

        var unique = ProgressResolver.uniqueNamedEpisodes(events);

        assertThat(unique).hasSize(2);
    }

    @Test
    void uniqueNamedEpisodesKeepsNullSeasonEntriesForAnySeasonResolution() {
        List<WatchEvent> events = List.of(named("Dark", null, "Secrets"));

        var unique = ProgressResolver.uniqueNamedEpisodes(events);

        assertThat(unique).hasSize(1);
        assertThat(unique.iterator().next().season()).isNull();
    }

    @Test
    void uniqueNamedEpisodesExcludesAlreadyNumberedEvents() {
        List<WatchEvent> events = List.of(numbered("Dark", 1, 1));

        var unique = ProgressResolver.uniqueNamedEpisodes(events);

        assertThat(unique).isEmpty();
    }

    // --- Named-episode resolution: id-scoped / any-season / part fallbacks ---

    @Test
    void namedEpisodeResolvedByIdScopedSeasonWhenMetadataPresent() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(named("Dark", 1, "Secrets"));
        ShowMetadata md = metadata(70523, "Dark", ShowType.SERIES);
        FakeProvider provider = new FakeProvider();
        provider.idScopedResult = Optional.of(new WatchProgress(ShowType.SERIES, 1, 3, "TMDB"));
        ProgressResolver resolver = new ProgressResolver(provider, new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.of(md));

        assertThat(progress).hasValueSatisfying(p -> {
            assertThat(p.season()).isEqualTo(1);
            assertThat(p.episode()).isEqualTo(3);
        });
        assertThat(provider.idScopedCalls).isEqualTo(1);
        assertThat(provider.lastProviderId).isEqualTo(70523L);
        assertThat(provider.idAnySeasonCalls).isZero();
    }

    @Test
    void namedEpisodeFallsBackToIdAnySeasonWhenScopedMisses() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(named("Dark", 1, "Secrets"));
        ShowMetadata md = metadata(70523, "Dark", ShowType.SERIES);
        FakeProvider provider = new FakeProvider();
        provider.idScopedResult = Optional.empty();
        provider.idAnySeasonResult = Optional.of(new WatchProgress(ShowType.SERIES, 2, 1, "TMDB"));
        ProgressResolver resolver = new ProgressResolver(provider, new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.of(md));

        assertThat(progress).hasValueSatisfying(p -> assertThat(p.season()).isEqualTo(2));
        assertThat(provider.idScopedCalls).isEqualTo(1);
        assertThat(provider.idAnySeasonCalls).isEqualTo(1);
    }

    @Test
    void namedEpisodeUsesTitleLookupWhenNoMetadataAvailable() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(named("Dark", 1, "Secrets"));
        FakeProvider provider = new FakeProvider();
        provider.titleScopedResult = Optional.of(new WatchProgress(ShowType.SERIES, 1, 4, "TVMAZE"));
        ProgressResolver resolver = new ProgressResolver(provider, new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.empty());

        assertThat(progress).hasValueSatisfying(p -> assertThat(p.episode()).isEqualTo(4));
        assertThat(provider.lastTitleLookup).isEqualTo("Dark");
        assertThat(provider.idScopedCalls).isZero();
    }

    @Test
    void namedEpisodeRetriesWithTrailingPartStripped() {
        // Full title "Free Radicals, Part 2" fails; the provider's real episode title is "Free
        // Radicals" with no part suffix at all.
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(named("Dark", 1, "Free Radicals, Part 2"));
        ShowMetadata md = metadata(70523, "Dark", ShowType.SERIES);
        FakeProvider provider = new FakeProvider();
        provider.idScopedResult = Optional.empty();
        provider.idAnySeasonResult = Optional.empty();
        provider.idScopedResultForStripped = Optional.of(new WatchProgress(ShowType.SERIES, 1, 6, "TMDB"));
        ProgressResolver resolver = new ProgressResolver(provider, new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.of(md));

        assertThat(progress).hasValueSatisfying(p -> assertThat(p.episode()).isEqualTo(6));
    }

    @Test
    void namedEpisodeRetriesWithParenthesizedPartNumber() {
        // TMDb's real convention for a multi-part episode: "Hell Week (2)", not ", Part 2".
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(named("Dark", 1, "Hell Week, Part two"));
        ShowMetadata md = metadata(70523, "Dark", ShowType.SERIES);
        FakeProvider provider = new FakeProvider();
        provider.idScopedResult = Optional.empty();
        provider.idAnySeasonResult = Optional.empty();
        provider.idScopedResultForStripped = Optional.empty();
        provider.idScopedResultForParenthesized = Optional.of(new WatchProgress(ShowType.SERIES, 1, 9, "TMDB"));
        ProgressResolver resolver = new ProgressResolver(provider, new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.of(md));

        assertThat(progress).hasValueSatisfying(p -> assertThat(p.episode()).isEqualTo(9));
        assertThat(provider.lastParenthesizedTitle).isEqualTo("Hell Week (2)");
    }

    @Test
    void namedEpisodeUnresolvedWhenAllAttemptsMiss() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(named("Dark", 1, "Secrets"));
        ShowMetadata md = metadata(70523, "Dark", ShowType.SERIES);
        ProgressResolver resolver = new ProgressResolver(new FakeProvider(), new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.of(md));

        assertThat(progress).isEmpty();
    }

    @Test
    void providerFailureDuringEpisodeResolutionIsTreatedAsMiss() {
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 1);
        List<WatchEvent> events = List.of(named("Dark", 1, "Secrets"));
        ShowMetadata md = metadata(70523, "Dark", ShowType.SERIES);
        MetadataProvider throwing = new StubProvider() {
            @Override
            public Optional<WatchProgress> resolveEpisode(long providerId, int season, String episodeTitle) {
                throw new RuntimeException("boom");
            }
        };
        ProgressResolver resolver = new ProgressResolver(throwing, new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.of(md));

        assertThat(progress).isEmpty();
    }

    // --- Named + numbered merge: distinct counting -----------------------------

    @Test
    void namedEpisodeResolvedToSamePositionAsANumberedEventCountsOnceForCompletion() {
        // S1E1 watched both as a Netflix-numbered event and (independently) as a named episode
        // that resolves to the same (season, episode): must count as one distinct position, not
        // two, or completion status would over-count.
        Show show = show(UUID.randomUUID(), "Dark", Set.of(), 2);
        List<WatchEvent> events = List.of(numbered("Dark", 1, 1), named("Dark", 1, "Secrets"));
        ShowMetadata md = new ShowMetadata("TMDB", 70523, "Dark", ShowType.SERIES, List.of(), null, null, null, 2, true);
        FakeProvider provider = new FakeProvider();
        provider.idScopedResult = Optional.of(new WatchProgress(ShowType.SERIES, 1, 1, "TMDB")); // same position
        ProgressResolver resolver = new ProgressResolver(provider, new NoOpDiagnosticsCollector());

        Optional<WatchProgress> progress = resolver.resolve(show, events, ShowType.SERIES, Optional.of(md));

        // Only 1 distinct episode out of 2 total -> IN_PROGRESS, not COMPLETED as it would be if
        // the merge double-counted the same position from two sources.
        assertThat(progress).hasValueSatisfying(p -> assertThat(p.status()).isEqualTo(CompletionStatus.IN_PROGRESS));
    }

    // --- seedSeriesMappings -----------------------------------------------------

    @Test
    void seedSeriesMappingsRegistersOriginalStrippedAndProviderTitles() {
        FakeProvider provider = new FakeProvider();
        ProgressResolver resolver = new ProgressResolver(provider, new NoOpDiagnosticsCollector());
        ShowMetadata md = metadata(97400, "Midnight Mass", ShowType.LIMITED_SERIES);

        resolver.seedSeriesMappings("Midnight Mass: Limited Series", md);

        assertThat(provider.rememberedMappings)
                .containsEntry("Midnight Mass: Limited Series", 97400L)
                .containsEntry("Midnight Mass", 97400L);
    }

    @Test
    void seedSeriesMappingsDoesNotDuplicateWhenStrippedEqualsOriginal() {
        FakeProvider provider = new FakeProvider();
        ProgressResolver resolver = new ProgressResolver(provider, new NoOpDiagnosticsCollector());
        ShowMetadata md = metadata(70523, "Dark", ShowType.SERIES);

        resolver.seedSeriesMappings("Dark", md);

        assertThat(provider.rememberedMappings).containsEntry("Dark", 70523L);
    }

    // --- Fakes ---------------------------------------------------------------

    /** A provider that never resolves anything; safe default for tests that don't need lookups. */
    private static class StubProvider implements MetadataProvider {
        @Override
        public Optional<ShowMetadata> findByTitle(String title) {
            return Optional.empty();
        }
    }

    /** Configurable fake distinguishing id-based vs title-based, scoped vs any-season calls. */
    private static class FakeProvider implements MetadataProvider {
        Optional<WatchProgress> idScopedResult = Optional.empty();
        Optional<WatchProgress> idAnySeasonResult = Optional.empty();
        Optional<WatchProgress> idScopedResultForStripped = Optional.empty();
        Optional<WatchProgress> idScopedResultForParenthesized = Optional.empty();
        Optional<WatchProgress> titleScopedResult = Optional.empty();
        Optional<WatchProgress> titleAnySeasonResult = Optional.empty();

        int idScopedCalls;
        int idAnySeasonCalls;
        long lastProviderId;
        String lastTitleLookup;
        String lastParenthesizedTitle;

        final java.util.Map<String, Long> rememberedMappings = new java.util.LinkedHashMap<>();

        @Override
        public Optional<ShowMetadata> findByTitle(String title) {
            return Optional.empty();
        }

        @Override
        public Optional<WatchProgress> resolveEpisode(long providerId, int season, String episodeTitle) {
            idScopedCalls++;
            lastProviderId = providerId;
            if (episodeTitle != null && episodeTitle.endsWith(")")) {
                lastParenthesizedTitle = episodeTitle;
                return idScopedResultForParenthesized;
            }
            if ("Free Radicals".equals(episodeTitle)) {
                return idScopedResultForStripped;
            }
            return idScopedResult;
        }

        @Override
        public Optional<WatchProgress> resolveEpisodeAnySeason(long providerId, String episodeTitle) {
            idAnySeasonCalls++;
            lastProviderId = providerId;
            if (episodeTitle != null && episodeTitle.endsWith(")")) {
                lastParenthesizedTitle = episodeTitle;
                return idScopedResultForParenthesized;
            }
            if ("Free Radicals".equals(episodeTitle)) {
                return idScopedResultForStripped;
            }
            return idAnySeasonResult;
        }

        @Override
        public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
            lastTitleLookup = showTitle;
            return titleScopedResult;
        }

        @Override
        public Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
            lastTitleLookup = showTitle;
            return titleAnySeasonResult;
        }

        @Override
        public void rememberSeriesMapping(String title, long providerId) {
            if (title != null && !title.isBlank() && providerId > 0) {
                rememberedMappings.put(title.trim(), providerId);
            }
        }
    }
}

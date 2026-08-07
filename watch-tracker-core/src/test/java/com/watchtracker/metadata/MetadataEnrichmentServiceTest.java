package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.exporter.WatchHistoryExporter;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import com.watchtracker.model.WatchEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tests enrichment without requiring network access or an API token. */
class MetadataEnrichmentServiceTest {
    @Test
    void retainsProviderMetadataSeparatelyFromWatchHistory() {
        Show show = new Show(
                UUID.randomUUID(),
                "Dark",
                Set.of("Dark: Episode 1"),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                1,
                1);
        MetadataProvider provider = title -> Optional.of(
                new ShowMetadata("TMDB", 70523, title, ShowType.SERIES, List.of("Drama", "Mystery"), null, null, null));

        EnrichedShow result =
                new MetadataEnrichmentService(provider).enrich(List.of(show)).getFirst();

        assertThat(result.show().type()).isEqualTo(ShowType.UNKNOWN);
        assertThat(result.metadata())
                .hasValueSatisfying(metadata -> assertThat(metadata.genres()).contains("Drama", "Mystery"));
    }

    @Test
    void exportsMetadataAsAnObjectInsteadOfOptionalImplementationDetails() throws java.io.IOException {
        Show show = new Show(
                UUID.randomUUID(),
                "Dark",
                Set.of(),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        EnrichedShow enriched = new EnrichedShow(
                show,
                Optional.of(
                        new ShowMetadata("TMDB", 70523, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null)));
        java.nio.file.Path output = java.nio.file.Files.createTempFile("watch-tracker", ".json");

        new WatchHistoryExporter().exportEnrichedJson(List.of(enriched), output);

        assertThat(java.nio.file.Files.readString(output)).contains("\"metadata\" : {", "\"genres\" : [ \"Drama\" ]");
    }

    @Test
    void reusesPreviousResultWithoutCallingProviderWhenShowIsUnchanged() {
        UUID showId = UUID.randomUUID();
        Set<String> aliases = Set.of("Dark: Chapter One", "Dark: Chapter Two");
        Show currentShow = new Show(
                showId,
                "Dark",
                aliases,
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                1,
                2,
                2);
        // Same watch count, highest season, highest episode, and aliases as the current show;
        // only the previously-resolved type and timestamps differ, which should not matter.
        Show previousShow = new Show(
                showId,
                "Dark",
                aliases,
                ShowType.SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                1,
                2,
                2);
        ShowMetadata previousMetadata =
                new ShowMetadata("TMDB", 70523, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null);
        WatchProgress previousProgress = new WatchProgress(1, 2, "TVMAZE");
        EnrichedShow previousResult =
                new EnrichedShow(previousShow, Optional.of(previousMetadata), Optional.of(previousProgress));
        PreviousEnrichmentSnapshot previous = id -> id.equals(showId) ? Optional.of(previousResult) : Optional.empty();
        RecordingProvider provider = new RecordingProvider(Optional.empty(), Optional.empty());

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(currentShow), List.of(), previous)
                .getFirst();

        assertThat(result.metadata()).contains(previousMetadata);
        assertThat(result.progress()).contains(previousProgress);
        assertThat(provider.findByTitleCalls).isZero();
        assertThat(provider.resolveEpisodeCalls).isZero();
    }

    @Test
    void resolvesFreshWhenWatchCountHasChangedSincePreviousRun() {
        UUID showId = UUID.randomUUID();
        Set<String> aliases = Set.of("Dark: Chapter One");
        Show currentShow = new Show(
                showId,
                "Dark",
                aliases,
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 3, 0, 0),
                1,
                4,
                4);
        // One fewer watch event than the current show, so the previous result must not be reused.
        Show previousShow = new Show(
                showId,
                "Dark",
                aliases,
                ShowType.SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                1,
                3,
                3);
        EnrichedShow previousResult = new EnrichedShow(
                previousShow,
                Optional.of(
                        new ShowMetadata("TMDB", 70523, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null)),
                Optional.of(new WatchProgress(1, 3, "TVMAZE")));
        PreviousEnrichmentSnapshot previous = id -> id.equals(showId) ? Optional.of(previousResult) : Optional.empty();
        ShowMetadata freshMetadata =
                new ShowMetadata("TMDB", 70523, "Dark", ShowType.SERIES, List.of("Drama", "Mystery"), null, null, null);
        RecordingProvider provider = new RecordingProvider(Optional.of(freshMetadata), Optional.empty());
        WatchEvent numberedEvent = new WatchEvent(
                UUID.randomUUID(),
                "Dark: Season 1: Chapter 4",
                LocalDateTime.of(2024, 1, 3, 0, 0),
                "Netflix",
                "Dark",
                1,
                4,
                "Chapter Four",
                true);

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(currentShow), List.of(numberedEvent), previous)
                .getFirst();

        assertThat(result.metadata()).contains(freshMetadata);
        assertThat(result.progress()).contains(new WatchProgress(1, 4, "NETFLIX"));
        assertThat(provider.findByTitleCalls).isEqualTo(1);
    }

    @Test
    void resolvesFreshWhenAliasesHaveChangedSincePreviousRun() {
        UUID showId = UUID.randomUUID();
        Show currentShow = new Show(
                showId,
                "Dark",
                Set.of("Dark: Chapter One", "Dark: Chapter Two"),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                1,
                2,
                2);
        // Same watch count, season, and episode as current, but a different alias observed under a
        // platform title change; watch count alone cannot detect this, so aliases must be compared.
        Show previousShow = new Show(
                showId,
                "Dark",
                Set.of("Dark: Chapter One", "Dark: Part Two"),
                ShowType.SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                1,
                2,
                2);
        EnrichedShow previousResult = new EnrichedShow(
                previousShow,
                Optional.of(
                        new ShowMetadata("TMDB", 70523, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null)));
        PreviousEnrichmentSnapshot previous = id -> id.equals(showId) ? Optional.of(previousResult) : Optional.empty();
        RecordingProvider provider = new RecordingProvider(Optional.empty(), Optional.empty());

        new MetadataEnrichmentService(provider).enrich(List.of(currentShow), List.of(), previous);

        assertThat(provider.findByTitleCalls).isEqualTo(1);
    }

    @Test
    void stripsLimitedSeriesSuffixWhenOriginalTitleMisses() {
        Show show = new Show(
                UUID.randomUUID(),
                "The Act: Limited Series",
                Set.of("The Act: Limited Series: Episode 1"),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                1,
                1);
        ShowMetadata strippedMatch =
                new ShowMetadata("TMDB", 1, "The Act", ShowType.SERIES, List.of("Drama"), null, null, null);
        List<String> requestedTitles = new java.util.ArrayList<>();
        MetadataProvider provider = title -> {
            requestedTitles.add(title);
            return "The Act".equals(title) ? Optional.of(strippedMatch) : Optional.empty();
        };

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.metadata()).contains(strippedMatch);
        // The original title is tried first, only then the "Limited Series" suffix is stripped and retried.
        assertThat(requestedTitles).containsExactly("The Act: Limited Series", "The Act");
    }

    @Test
    void findsLimitedSeriesMatchFromPersistentCacheWithoutCallingDelegate() throws java.io.IOException {
        Show show = new Show(
                UUID.randomUUID(),
                "The Act: Limited Series",
                Set.of(),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        ShowMetadata cachedMatch =
                new ShowMetadata("TMDB", 1, "The Act", ShowType.SERIES, List.of("Drama"), null, null, null);
        java.nio.file.Path cacheDir = java.nio.file.Files.createTempDirectory("watch-tracker-cache-test");
        FileMetadataCache cache = new FileMetadataCache(cacheDir);
        cache.put("The Act", cachedMatch);
        RecordingProvider delegate = new RecordingProvider(Optional.empty(), Optional.empty());
        CachingMetadataProvider provider = new CachingMetadataProvider(delegate, cache);

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.metadata()).contains(cachedMatch);
        assertThat(delegate.findByTitleCalls).isZero();
    }

    @Test
    void documentaryGenreOverridesProviderType() {
        Show show = new Show(
                UUID.randomUUID(),
                "Wild Wild Country",
                Set.of(),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        ShowMetadata metadata = new ShowMetadata(
                "TMDB", 1, "Wild Wild Country", ShowType.SERIES, List.of("Documentary", "Crime"), null, null, null);
        MetadataProvider provider = title -> Optional.of(metadata);

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.show().type()).isEqualTo(ShowType.DOCUMENTARY);
    }

    @Test
    void documentaryGenreTakesPriorityOverLimitedSeriesAlias() {
        Show show = new Show(
                UUID.randomUUID(),
                "Wild Wild Country: Limited Series",
                Set.of(),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        ShowMetadata metadata =
                new ShowMetadata("TMDB", 1, "Wild Wild Country", ShowType.SERIES, List.of("Documentary"), null, null, null);
        MetadataProvider provider = title -> Optional.of(metadata);

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.show().type()).isEqualTo(ShowType.DOCUMENTARY);
    }

    @Test
    void limitedSeriesAliasPromotesSeriesTypeToLimitedSeries() {
        Show show = new Show(
                UUID.randomUUID(),
                "The Act",
                Set.of("The Act: Limited Series: Episode 1"),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                1,
                1);
        ShowMetadata metadata = new ShowMetadata("TMDB", 1, "The Act", ShowType.SERIES, List.of("Drama"), null, null, null);
        MetadataProvider provider = title -> Optional.of(metadata);

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.show().type()).isEqualTo(ShowType.LIMITED_SERIES);
    }

    @Test
    void movieTypeIsNotPromotedToLimitedSeriesEvenWithLimitedSeriesAlias() {
        Show show = new Show(
                UUID.randomUUID(),
                "Movie Title: Limited Series",
                Set.of(),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        ShowMetadata metadata =
                new ShowMetadata("TMDB", 1, "Movie Title", ShowType.MOVIE, List.of("Drama"), null, null, null);
        MetadataProvider provider = title -> Optional.of(metadata);

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.show().type()).isEqualTo(ShowType.MOVIE);
    }

    @Test
    void numberedEpisodeWithoutSeasonMarkerDefaultsToSeasonOne() {
        Show show = new Show(
                UUID.randomUUID(),
                "Adolescence",
                Set.of("Adolescence: Episode 3"),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                3,
                1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Adolescence: Episode 3",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Adolescence",
                null,
                3,
                null,
                true);
        MetadataProvider provider = title -> Optional.empty();

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).contains(new WatchProgress(1, 3, "NETFLIX"));
    }

    @Test
    void explicitSeasonOutranksDefaultedSeasonRegardlessOfEpisodeNumber() {
        Show show = new Show(
                UUID.randomUUID(),
                "Dark",
                Set.of(),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                2,
                1,
                2);
        // No season marker at all in the source title, so this defaults to season 1.
        WatchEvent unknownSeasonEvent = new WatchEvent(
                UUID.randomUUID(),
                "Dark: Episode 5",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Dark",
                null,
                5,
                null,
                true);
        // An explicit, later season that should still win the comparison despite the lower episode number.
        WatchEvent explicitSeasonEvent = new WatchEvent(
                UUID.randomUUID(),
                "Dark: Season 2: Episode 1",
                LocalDateTime.of(2024, 1, 2, 0, 0),
                "Netflix",
                "Dark",
                2,
                1,
                null,
                true);
        MetadataProvider provider = title -> Optional.empty();

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(unknownSeasonEvent, explicitSeasonEvent), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).contains(new WatchProgress(2, 1, "NETFLIX"));
    }

    @Test
    void namedEpisodeWithUnresolvedSeasonFallsBackToAnySeasonLookupInsteadOfBeingDropped() {
        Show show = new Show(
                UUID.randomUUID(),
                "Lockwood & Co.",
                Set.of("Lockwood & Co.: This Will Be Us"),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        // Netflix's raw title carried no season/container marker at all, so the normalizer leaves
        // both season and episode null; only the episode title survives.
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Lockwood & Co.: This Will Be Us",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Lockwood & Co.",
                null,
                null,
                "This Will Be Us",
                true);
        WatchProgress anySeasonMatch = new WatchProgress(1, 1, "TMDB");
        EpisodeResolutionRecordingProvider provider =
                new EpisodeResolutionRecordingProvider(Optional.empty(), Optional.of(anySeasonMatch));

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).contains(anySeasonMatch);
        assertThat(provider.anySeasonCalls).isEqualTo(1);
        assertThat(provider.scopedCalls).isZero();
    }

    @Test
    void namedEpisodeWithKnownSeasonUsesScopedLookupAndNeverFallsBackToAnySeason() {
        Show show = new Show(
                UUID.randomUUID(),
                "Dark",
                Set.of("Dark: Season 1: Secrets"),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                1,
                null,
                1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Dark: Season 1: Secrets",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Dark",
                1,
                null,
                "Secrets",
                true);
        WatchProgress scopedMatch = new WatchProgress(1, 3, "TMDB");
        WatchProgress anySeasonMatch = new WatchProgress(9, 9, "SHOULD_NOT_BE_USED");
        EpisodeResolutionRecordingProvider provider =
                new EpisodeResolutionRecordingProvider(Optional.of(scopedMatch), Optional.of(anySeasonMatch));

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).contains(scopedMatch);
        assertThat(provider.scopedCalls).isEqualTo(1);
        assertThat(provider.anySeasonCalls).isZero();
    }

    /** A provider that returns fixed results while counting how many times each method is called. */
    private static final class RecordingProvider implements MetadataProvider {
        private final Optional<ShowMetadata> metadataToReturn;
        private final Optional<WatchProgress> episodeToReturn;
        private int findByTitleCalls;
        private int resolveEpisodeCalls;

        RecordingProvider(Optional<ShowMetadata> metadataToReturn, Optional<WatchProgress> episodeToReturn) {
            this.metadataToReturn = metadataToReturn;
            this.episodeToReturn = episodeToReturn;
        }

        @Override
        public Optional<ShowMetadata> findByTitle(String title) {
            findByTitleCalls++;
            return metadataToReturn;
        }

        @Override
        public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
            resolveEpisodeCalls++;
            return episodeToReturn;
        }
    }

    /**
     * A provider that separately tracks and configures season-scoped versus season-agnostic named
     * episode resolution, so tests can assert which one was used.
     */
    private static final class EpisodeResolutionRecordingProvider implements MetadataProvider {
        private final Optional<WatchProgress> scopedResult;
        private final Optional<WatchProgress> anySeasonResult;
        private int scopedCalls;
        private int anySeasonCalls;

        EpisodeResolutionRecordingProvider(Optional<WatchProgress> scopedResult, Optional<WatchProgress> anySeasonResult) {
            this.scopedResult = scopedResult;
            this.anySeasonResult = anySeasonResult;
        }

        @Override
        public Optional<ShowMetadata> findByTitle(String title) {
            return Optional.empty();
        }

        @Override
        public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
            scopedCalls++;
            return scopedResult;
        }

        @Override
        public Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
            anySeasonCalls++;
            return anySeasonResult;
        }
    }
}
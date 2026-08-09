package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.DiagnosticsEntry;
import com.watchtracker.diagnostics.DiagnosticsStage;
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


    @Test
    void recordsEpisodeResolverFailureInDiagnosticsWhenNamedEpisodeCannotBeMatched() {
        Show show = new Show(
                UUID.randomUUID(),
                "Lockwood & Co.",
                Set.of("Lockwood & Co.: This Will Be Us"),
                ShowType.SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
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
        ShowMetadata metadata = new ShowMetadata(
                "TMDB", 213241, "Lockwood & Co.", ShowType.SERIES, List.of("Sci-Fi & Fantasy"), null, null, null);
        EpisodeResolutionRecordingProvider provider = new EpisodeResolutionRecordingProvider(
                Optional.of(metadata),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        RecordingDiagnosticsCollector diagnostics = new RecordingDiagnosticsCollector();

        EnrichedShow result = new MetadataEnrichmentService(provider, diagnostics)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).isEmpty();
        assertThat(diagnostics.entries)
                .anySatisfy(entry -> {
                    assertThat(entry.stage()).isEqualTo(DiagnosticsStage.EPISODE_RESOLVER);
                    assertThat(entry.message()).contains("Episode title not found");
                    assertThat(entry.input()).isNotNull();
                });
    }

    @Test
    void seedsSeriesMappingsForOriginalStrippedAndProviderTitlesAfterMetadataMatch() {
        Show show = new Show(
                UUID.randomUUID(),
                "My Royal Nemesis: Limited Series",
                Set.of("My Royal Nemesis: Limited Series: The Vixen and the Beast"),
                ShowType.LIMITED_SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        ShowMetadata metadata = new ShowMetadata(
                "TMDB", 303143, "My Royal Nemesis", ShowType.SERIES, List.of("Drama"), null, null, null);
        EpisodeResolutionRecordingProvider provider = new EpisodeResolutionRecordingProvider(
                Optional.of(metadata),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());

        new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(), PreviousEnrichmentSnapshot.EMPTY);

        assertThat(provider.rememberedMappings)
                .containsEntry("My Royal Nemesis: Limited Series", 303143L)
                .containsEntry("My Royal Nemesis", 303143L);
    }

    @Test
    void endToEndNamedOnlyTitlesProduceProgressViaProviderIdPath() {
        Show show = new Show(
                UUID.randomUUID(),
                "Taskaree: The Smuggler's Web",
                Set.of(
                        "Taskaree: The Smuggler's Web: Kahani Khatam",
                        "Taskaree: The Smuggler's Web: Operation Longshot"),
                ShowType.SERIES,
                LocalDateTime.of(2026, 2, 22, 0, 0),
                LocalDateTime.of(2026, 2, 22, 0, 0),
                null,
                null,
                2);
        List<WatchEvent> events = List.of(
                new WatchEvent(
                        UUID.randomUUID(),
                        "Taskaree: The Smuggler's Web: Kahani Khatam",
                        LocalDateTime.of(2026, 2, 22, 0, 0),
                        "Netflix",
                        "Taskaree: The Smuggler's Web",
                        null,
                        null,
                        "Kahani Khatam",
                        true),
                new WatchEvent(
                        UUID.randomUUID(),
                        "Taskaree: The Smuggler's Web: Operation Longshot",
                        LocalDateTime.of(2026, 2, 22, 0, 0),
                        "Netflix",
                        "Taskaree: The Smuggler's Web",
                        null,
                        null,
                        "Operation Longshot",
                        true));
        ShowMetadata metadata = new ShowMetadata(
                "TMDB",
                308482,
                "Taskaree: The Smuggler's Web",
                ShowType.SERIES,
                List.of("Crime"),
                null,
                null,
                null);
        // Id path resolves both titles; highest episode wins.
        EpisodeResolutionRecordingProvider provider = new EpisodeResolutionRecordingProvider(
                Optional.of(metadata),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new WatchProgress(1, 7, "TMDB"))) {
            @Override
            public Optional<WatchProgress> resolveEpisodeAnySeason(long providerId, String episodeTitle) {
                idAnySeasonCalls++;
                lastProviderId = providerId;
                if ("Operation Longshot".equals(episodeTitle)) {
                    return Optional.of(new WatchProgress(1, 7, "TMDB"));
                }
                if ("Kahani Khatam".equals(episodeTitle)) {
                    return Optional.of(new WatchProgress(1, 1, "TMDB"));
                }
                return Optional.empty();
            }
        };
        RecordingDiagnosticsCollector diagnostics = new RecordingDiagnosticsCollector();

        EnrichedShow result = new MetadataEnrichmentService(provider, diagnostics)
                .enrich(List.of(show), events, PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).contains(new WatchProgress(1, 7, "TMDB"));
        assertThat(provider.idAnySeasonCalls).isEqualTo(2);
        assertThat(provider.lastProviderId).isEqualTo(308482L);
        assertThat(diagnostics.entries)
                .filteredOn(e -> e.stage() == DiagnosticsStage.EPISODE_RESOLVER)
                .allSatisfy(e -> assertThat(e.message()).contains("Episode title resolved"));
    }

    @Test
    void prefersProviderIdResolutionWhenMetadataIsPresent() {
        Show show = new Show(
                UUID.randomUUID(),
                "My Royal Nemesis: Limited Series",
                Set.of("My Royal Nemesis: Limited Series: The Vixen and the Beast"),
                ShowType.LIMITED_SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "My Royal Nemesis: Limited Series: The Vixen and the Beast",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "My Royal Nemesis: Limited Series",
                null,
                null,
                "The Vixen and the Beast",
                true);
        ShowMetadata metadata = new ShowMetadata(
                "TMDB", 303143, "My Royal Nemesis", ShowType.SERIES, List.of("Drama"), null, null, null);
        WatchProgress idMatch = new WatchProgress(1, 2, "TMDB");
        EpisodeResolutionRecordingProvider provider = new EpisodeResolutionRecordingProvider(
                Optional.of(metadata),
                Optional.of(new WatchProgress(9, 9, "TITLE_SHOULD_NOT_BE_USED")),
                Optional.of(new WatchProgress(8, 8, "TITLE_ANY_SHOULD_NOT_BE_USED")),
                Optional.empty(),
                Optional.of(idMatch));

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).contains(new WatchProgress(ShowType.LIMITED_SERIES, 1, 2, "TMDB"));
        assertThat(provider.idAnySeasonCalls).isEqualTo(1);
        assertThat(provider.lastProviderId).isEqualTo(303143L);
        assertThat(provider.scopedCalls).isZero();
        assertThat(provider.anySeasonCalls).isZero();
    }

    @Test
    void providerIdScopedMissFallsBackToAnySeasonOnSameId() {
        // Netflix container/season numbers often disagree with TMDb; scoped miss must still try any-season.
        Show show = new Show(
                UUID.randomUUID(),
                "Dark",
                Set.of("Dark: Season 3: Secrets"),
                ShowType.SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                3,
                null,
                1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Dark: Season 3: Secrets",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Dark",
                3,
                null,
                "Secrets",
                true);
        ShowMetadata metadata =
                new ShowMetadata("TMDB", 70523, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null);
        WatchProgress anySeasonMatch = new WatchProgress(1, 5, "TMDB");
        EpisodeResolutionRecordingProvider provider = new EpisodeResolutionRecordingProvider(
                Optional.of(metadata),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(anySeasonMatch));

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).contains(new WatchProgress(ShowType.SERIES, 1, 5, "TMDB"));
        assertThat(provider.idScopedCalls).isEqualTo(1);
        assertThat(provider.idAnySeasonCalls).isEqualTo(1);
        assertThat(provider.anySeasonCalls).isZero();
    }

    @Test
    void doesNotRepeatTitleBasedAnySeasonWhenProviderIdPathAlreadyMissed() {
        // Once metadata identifies the series, a failed id-based guide scan must not fall through
        // to title-based any-season (duplicate HTTP over the same seasons).
        Show show = new Show(
                UUID.randomUUID(),
                "Boyfriend on Demand: Limited Series",
                Set.of("Boyfriend on Demand: Limited Series: Cliche or Classic"),
                ShowType.LIMITED_SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Boyfriend on Demand: Limited Series: Cliche or Classic",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Boyfriend on Demand: Limited Series",
                null,
                null,
                "Cliche or Classic",
                true);
        ShowMetadata metadata = new ShowMetadata(
                "TMDB", 281016, "Boyfriend on Demand", ShowType.SERIES, List.of("Comedy"), null, null, null);
        WatchProgress titleMatch = new WatchProgress(1, 3, "TMDB");
        EpisodeResolutionRecordingProvider provider = new EpisodeResolutionRecordingProvider(
                Optional.of(metadata),
                Optional.empty(),
                Optional.of(titleMatch),
                Optional.empty(),
                Optional.empty());

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).isEmpty();
        assertThat(provider.idAnySeasonCalls).isEqualTo(1);
        assertThat(provider.anySeasonCalls).isZero();
    }

    @Test
    void stripsLimitedSeriesFromShowTitleWhenNoMetadataTitleIsAvailable() {
        Show show = new Show(
                UUID.randomUUID(),
                "Dynamite Kiss: Limited Series",
                Set.of("Dynamite Kiss: Limited Series: Gone With a Kiss"),
                ShowType.LIMITED_SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Dynamite Kiss: Limited Series: Gone With a Kiss",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Dynamite Kiss: Limited Series",
                null,
                null,
                "Gone With a Kiss",
                true);
        WatchProgress titleMatch = new WatchProgress(1, 1, "TMDB");
        EpisodeResolutionRecordingProvider provider =
                new EpisodeResolutionRecordingProvider(Optional.empty(), Optional.of(titleMatch));

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.progress()).contains(new WatchProgress(ShowType.LIMITED_SERIES, 1, 1, "TMDB"));
        assertThat(provider.anySeasonCalls).isEqualTo(1);
        assertThat(provider.lastTitleLookup).isEqualTo("Dynamite Kiss");
    }

    @Test
    void resolvesMovieWatchProgressWhenMovieIsWatched() {
        Show show = new Show(
                UUID.randomUUID(),
                "Inception",
                Set.of(),
                ShowType.MOVIE,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Inception",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Inception",
                null,
                null,
                null,
                true);
        ShowMetadata movieMetadata =
                new ShowMetadata("TMDB", 27205, "Inception", ShowType.MOVIE, List.of("Sci-Fi"), null, null, null);
        MetadataProvider provider = title -> Optional.of(movieMetadata);

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.show().type()).isEqualTo(ShowType.MOVIE);
        assertThat(result.progress()).isPresent();
        WatchProgress progress = result.progress().get();
        assertThat(progress.type()).isEqualTo(ShowType.MOVIE);
        assertThat(progress.season()).isNull();
        assertThat(progress.episode()).isNull();
        assertThat(progress.status()).isEqualTo(com.watchtracker.model.CompletionStatus.COMPLETED);
    }

    @Test
    void propagatesSpecificShowTypeToWatchProgress() {
        Show show = new Show(
                UUID.randomUUID(),
                "Chernobyl",
                Set.of("Chernobyl: Episode 1"),
                ShowType.LIMITED_SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                1,
                1,
                1);
        WatchEvent event = new WatchEvent(
                UUID.randomUUID(),
                "Chernobyl: Episode 1",
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                "Chernobyl",
                1,
                1,
                "1:23:45",
                true);
        ShowMetadata metadata =
                new ShowMetadata("TMDB", 87108, "Chernobyl", ShowType.LIMITED_SERIES, List.of("Drama"), null, null, null);
        MetadataProvider provider = title -> Optional.of(metadata);

        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), List.of(event), PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();

        assertThat(result.show().type()).isEqualTo(ShowType.LIMITED_SERIES);
        assertThat(result.progress()).isPresent();
        assertThat(result.progress().get().type()).isEqualTo(ShowType.LIMITED_SERIES);
    }

    @Test
    void supportsUnknownShowTypeInWatchProgressAndChecksCompareToConsistency() {
        WatchProgress wp1 = new WatchProgress(ShowType.UNKNOWN, 1, 1, "TMDB");
        WatchProgress wp2 = new WatchProgress(ShowType.UNKNOWN, 1, 1, "TMDB");
        WatchProgress wp3 = new WatchProgress(ShowType.UNKNOWN, 1, 2, "TMDB");

        assertThat(wp1.compareTo(wp2)).isZero();
        assertThat(wp1.compareTo(wp3)).isNegative();
        assertThat(wp1.type()).isEqualTo(ShowType.UNKNOWN);
    }

    @Test
    void completionStatusIsInProgressWhenFewerDistinctEpisodesWatchedThanTotal() {
        Show show = seriesShow("Dark", 1, 3, 3);
        List<WatchEvent> events =
                List.of(numberedEvent("Dark", 1, 1), numberedEvent("Dark", 1, 2), numberedEvent("Dark", 1, 3));
        ShowMetadata metadata = seriesMetadata("Dark", 10, null);

        WatchProgress progress = enrichAndGetProgress(show, events, metadata);

        assertThat(progress.status()).isEqualTo(com.watchtracker.model.CompletionStatus.IN_PROGRESS);
        assertThat(progress.season()).isEqualTo(1);
        assertThat(progress.episode()).isEqualTo(3);
    }

    @Test
    void completionStatusIsCompletedWhenAllDistinctEpisodesWatchedAndSeriesHasEnded() {
        Show show = seriesShow("Dark", 1, 3, 3);
        List<WatchEvent> events =
                List.of(numberedEvent("Dark", 1, 1), numberedEvent("Dark", 1, 2), numberedEvent("Dark", 1, 3));
        ShowMetadata metadata = seriesMetadata("Dark", 3, true);

        WatchProgress progress = enrichAndGetProgress(show, events, metadata);

        assertThat(progress.status()).isEqualTo(com.watchtracker.model.CompletionStatus.COMPLETED);
    }

    @Test
    void completionStatusIsInProgressWhenCaughtUpButSeriesIsStillAiring() {
        // Every currently-released episode has been watched, but the show isn't over yet, so this
        // must not be reported as COMPLETED -- more of the story is still to come.
        Show show = seriesShow("Stranger Things", 4, 1, 1);
        List<WatchEvent> events = List.of(numberedEvent("Stranger Things", 4, 1));
        ShowMetadata metadata = seriesMetadata("Stranger Things", 1, false);

        WatchProgress progress = enrichAndGetProgress(show, events, metadata);

        assertThat(progress.status()).isEqualTo(com.watchtracker.model.CompletionStatus.IN_PROGRESS);
    }

    @Test
    void completionStatusIsUnknownWhenProviderDoesNotReportATotalEpisodeCount() {
        Show show = seriesShow("Obscure Show", 1, 1, 1);
        List<WatchEvent> events = List.of(numberedEvent("Obscure Show", 1, 1));
        ShowMetadata metadata = seriesMetadata("Obscure Show", null, null);

        WatchProgress progress = enrichAndGetProgress(show, events, metadata);

        assertThat(progress.status()).isEqualTo(com.watchtracker.model.CompletionStatus.UNKNOWN);
    }

    @Test
    void completionStatusCountsDistinctEpisodesRatherThanHighestPositionReached() {
        // Season 1 watched in full, then a jump straight to season 3 episode 1: the highest
        // position is "high", but only 4 of 10 distinct episodes have actually been watched.
        Show show = seriesShow("Skip Ahead Show", 3, 1, 5);
        List<WatchEvent> events = List.of(
                numberedEvent("Skip Ahead Show", 1, 1),
                numberedEvent("Skip Ahead Show", 1, 2),
                numberedEvent("Skip Ahead Show", 1, 3),
                numberedEvent("Skip Ahead Show", 3, 1));
        ShowMetadata metadata = seriesMetadata("Skip Ahead Show", 10, true);

        WatchProgress progress = enrichAndGetProgress(show, events, metadata);

        // The reported position is still the highest one reached...
        assertThat(progress.season()).isEqualTo(3);
        assertThat(progress.episode()).isEqualTo(1);
        // ...but status reflects the true distinct count (4 of 10), not the high-looking position.
        assertThat(progress.status()).isEqualTo(com.watchtracker.model.CompletionStatus.IN_PROGRESS);
    }

    @Test
    void completionStatusCountsRepeatedWatchesOfTheSameEpisodeOnlyOnce() {
        Show show = seriesShow("Rewatched Show", 1, 1, 2);
        List<WatchEvent> events = List.of(
                numberedEvent("Rewatched Show", 1, 1),
                numberedEvent("Rewatched Show", 1, 1), // rewatch / duplicate row
                numberedEvent("Rewatched Show", 1, 2));
        ShowMetadata metadata = seriesMetadata("Rewatched Show", 2, true);

        WatchProgress progress = enrichAndGetProgress(show, events, metadata);

        // Only 2 distinct episodes were watched, matching the total, so this is COMPLETED --
        // if the duplicate row were double-counted this would look like 3 of 2, which isn't
        // meaningful, but the outcome is still correct either way here; the real assertion is in
        // completionStatusCountsDistinctEpisodesRatherThanHighestPositionReached above and in the
        // diagnostics-based test below.
        assertThat(progress.status()).isEqualTo(com.watchtracker.model.CompletionStatus.COMPLETED);
    }

    @Test
    void diagnosticsReportTheTrueDistinctCountNotTheRawEventCount() {
        Show show = seriesShow("Rewatched Show", 1, 1, 1);
        List<WatchEvent> events = List.of(
                numberedEvent("Rewatched Show", 1, 1),
                numberedEvent("Rewatched Show", 1, 1),
                numberedEvent("Rewatched Show", 1, 1));
        ShowMetadata metadata = seriesMetadata("Rewatched Show", 5, false);
        RecordingDiagnosticsCollector diagnostics = new RecordingDiagnosticsCollector();
        MetadataProvider provider = title -> Optional.of(metadata);

        new MetadataEnrichmentService(provider, diagnostics)
                .enrich(List.of(show), events, PreviousEnrichmentSnapshot.EMPTY);

        assertThat(diagnostics.entries)
                .anySatisfy(entry -> {
                    assertThat(entry.message()).contains("Completion status");
                    assertThat(entry.input()).isInstanceOfSatisfying(java.util.Map.class, input -> {
                        // Three watch events for the same episode must still count as one distinct
                        // episode, not three, in what actually drives the completion-status decision.
                        assertThat(input.get("distinctEpisodesWatched")).isEqualTo(1);
                    });
                });
    }

    @Test
    void completionStatusMergesNumberedAndNamedPositionsWithoutDoubleCounting() {
        Show show = new Show(
                UUID.randomUUID(),
                "Mixed Show",
                Set.of("Mixed Show: Episode 1", "Mixed Show: A Named One"),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                1,
                1,
                2);
        WatchEvent numbered = numberedEvent("Mixed Show", 1, 1);
        WatchEvent named = new WatchEvent(
                UUID.randomUUID(),
                "Mixed Show: A Named One",
                LocalDateTime.of(2024, 1, 2, 0, 0),
                "Netflix",
                "Mixed Show",
                1,
                null,
                "A Named One",
                true);
        ShowMetadata metadata = seriesMetadata("Mixed Show", 2, true);
        EpisodeResolutionRecordingProvider provider = new EpisodeResolutionRecordingProvider(
                Optional.of(metadata),
                Optional.empty(),
                Optional.empty(),
                Optional.of(new WatchProgress(1, 2, "TMDB")),
                Optional.empty());

        WatchProgress progress = enrichAndGetProgress(show, List.of(numbered, named), provider);

        // 2 distinct episodes (season 1 episode 1 from Netflix numbering, season 1 episode 2 from
        // the resolved named episode) match the total of 2, so this is COMPLETED.
        assertThat(progress.status()).isEqualTo(com.watchtracker.model.CompletionStatus.COMPLETED);
    }

    /** Builds a minimal series Show for completion-status tests. */
    private static Show seriesShow(String title, int highestSeason, int highestEpisode, int watchCount) {
        return new Show(
                UUID.randomUUID(),
                title,
                Set.of(),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 2, 0, 0),
                highestSeason,
                highestEpisode,
                watchCount);
    }

    /** Builds a Netflix-numbered watch event for the given show, season, and episode. */
    private static WatchEvent numberedEvent(String canonicalShow, int season, int episode) {
        return new WatchEvent(
                UUID.randomUUID(),
                canonicalShow + ": Season " + season + ": Episode " + episode,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                "Netflix",
                canonicalShow,
                season,
                episode,
                null,
                true);
    }

    /** Builds series metadata with the given total episode count and production-ended flag. */
    private static ShowMetadata seriesMetadata(String title, Integer totalEpisodes, Boolean productionEnded) {
        return new ShowMetadata(
                "TMDB", 1, title, ShowType.SERIES, List.of("Drama"), null, null, null, totalEpisodes, productionEnded);
    }

    /** Runs enrichment for one show against a provider that always returns the given metadata. */
    private static WatchProgress enrichAndGetProgress(Show show, List<WatchEvent> events, ShowMetadata metadata) {
        MetadataProvider provider = title -> Optional.of(metadata);
        return enrichAndGetProgress(show, events, provider);
    }

    /** Runs enrichment for one show against the given provider and returns the resolved progress. */
    private static WatchProgress enrichAndGetProgress(Show show, List<WatchEvent> events, MetadataProvider provider) {
        EnrichedShow result = new MetadataEnrichmentService(provider)
                .enrich(List.of(show), events, PreviousEnrichmentSnapshot.EMPTY)
                .getFirst();
        assertThat(result.progress()).isPresent();
        return result.progress().get();
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
    private static class EpisodeResolutionRecordingProvider implements MetadataProvider {
        private final Optional<ShowMetadata> metadataToReturn;
        private final Optional<WatchProgress> scopedResult;
        private final Optional<WatchProgress> anySeasonResult;
        private final Optional<WatchProgress> idScopedResult;
        private final Optional<WatchProgress> idAnySeasonResult;
        // Package-private so anonymous subclasses in tests can update counters.
        int scopedCalls;
        int anySeasonCalls;
        int idScopedCalls;
        int idAnySeasonCalls;
        String lastTitleLookup;
        long lastProviderId;
        final java.util.Map<String, Long> rememberedMappings = new java.util.LinkedHashMap<>();

        EpisodeResolutionRecordingProvider(Optional<WatchProgress> scopedResult, Optional<WatchProgress> anySeasonResult) {
            this(Optional.empty(), scopedResult, anySeasonResult, Optional.empty(), Optional.empty());
        }

        EpisodeResolutionRecordingProvider(
                Optional<ShowMetadata> metadataToReturn,
                Optional<WatchProgress> scopedResult,
                Optional<WatchProgress> anySeasonResult,
                Optional<WatchProgress> idScopedResult,
                Optional<WatchProgress> idAnySeasonResult) {
            this.metadataToReturn = metadataToReturn;
            this.scopedResult = scopedResult;
            this.anySeasonResult = anySeasonResult;
            this.idScopedResult = idScopedResult;
            this.idAnySeasonResult = idAnySeasonResult;
        }

        @Override
        public Optional<ShowMetadata> findByTitle(String title) {
            return metadataToReturn;
        }

        @Override
        public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
            scopedCalls++;
            lastTitleLookup = showTitle;
            return scopedResult;
        }

        @Override
        public Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
            anySeasonCalls++;
            lastTitleLookup = showTitle;
            return anySeasonResult;
        }

        @Override
        public Optional<WatchProgress> resolveEpisode(long providerId, int season, String episodeTitle) {
            idScopedCalls++;
            lastProviderId = providerId;
            return idScopedResult;
        }

        @Override
        public Optional<WatchProgress> resolveEpisodeAnySeason(long providerId, String episodeTitle) {
            idAnySeasonCalls++;
            lastProviderId = providerId;
            return idAnySeasonResult;
        }

        @Override
        public void rememberSeriesMapping(String title, long providerId) {
            if (title != null && !title.isBlank() && providerId > 0) {
                rememberedMappings.put(title.trim(), providerId);
            }
        }
    }

    /** Collects diagnostics entries for assertions without writing files. */
    private static final class RecordingDiagnosticsCollector implements DiagnosticsCollector {
        private final List<DiagnosticsEntry> entries = new java.util.ArrayList<>();

        @Override
        public void record(DiagnosticsEntry entry) {
            entries.add(entry);
        }
    }
}
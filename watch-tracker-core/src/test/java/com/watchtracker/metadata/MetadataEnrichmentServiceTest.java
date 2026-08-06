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
}

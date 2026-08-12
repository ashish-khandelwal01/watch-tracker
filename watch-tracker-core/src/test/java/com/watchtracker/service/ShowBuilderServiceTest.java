package com.watchtracker.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.metadata.MetadataOverride;
import com.watchtracker.metadata.MetadataOverrideStore;
import com.watchtracker.model.Show;
import com.watchtracker.model.WatchEvent;
import com.watchtracker.normalizer.TitleNormalizer;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests conservative grouping of normalized watch events. */
class ShowBuilderServiceTest {
    @Test
    void groupsEventsAndDoesNotInferCompletion() {
        TitleNormalizer normalizer = new TitleNormalizer();
        List<WatchEvent> events = List.of(
                normalizer.normalize(WatchEvent.raw("Dark: Episode 1", LocalDateTime.of(2024, 1, 2, 0, 0), "NETFLIX")),
                normalizer.normalize(WatchEvent.raw("Dark: Episode 3", LocalDateTime.of(2024, 1, 1, 0, 0), "NETFLIX")));

        Show show = new ShowBuilderService().build(events).getFirst();

        assertThat(show.title()).isEqualTo("Dark");
        assertThat(show.watchCount()).isEqualTo(2);
        assertThat(show.highestEpisodeSeen()).isEqualTo(3);
        assertThat(show.firstWatched()).isEqualTo(LocalDateTime.of(2024, 1, 1, 0, 0));
    }

    @Test
    void countsNamedEpisodesInTheirNetflixPart() {
        TitleNormalizer normalizer = new TitleNormalizer();
        List<WatchEvent> events = List.of(
                normalizer.normalize(WatchEvent.raw("Money Heist: Part 2: Episode 9", LocalDateTime.now(), "NETFLIX")),
                normalizer.normalize(WatchEvent.raw("Money Heist: Part 3: Aikido", LocalDateTime.now(), "NETFLIX")),
                normalizer.normalize(
                        WatchEvent.raw("Money Heist: Part 3: We're Back", LocalDateTime.now(), "NETFLIX")));

        Show show = new ShowBuilderService().build(events).getFirst();

        assertThat(show.highestSeasonSeen()).isEqualTo(3);
        assertThat(show.watchCount()).isEqualTo(3);
    }

    @Test
    void consolidatesEpisodeRecordsWhenOverrideMatchesBaseTitle() {
        TitleNormalizer normalizer = new TitleNormalizer();
        List<WatchEvent> events = List.of(
                normalizer.normalize(WatchEvent.raw("The Lady's Companion: Lesson 1", LocalDateTime.of(2025, 11, 18, 0, 0), "NETFLIX")),
                normalizer.normalize(WatchEvent.raw("The Lady's Companion: Lesson 2", LocalDateTime.of(2025, 11, 18, 0, 0), "NETFLIX")));

        // Create an override for the base show title
        MetadataOverride override = new MetadataOverride("The Lady's Companion", "TMDB", "tv", 280890);
        MetadataOverrideStore overrides = MetadataOverrideStore.of(List.of(override));

        List<Show> shows = new ShowBuilderService(new NoOpDiagnosticsCollector(), overrides).build(events);

        // Should consolidate into a single show with the override title
        assertThat(shows).hasSize(1);
        Show show = shows.getFirst();
        assertThat(show.title()).isEqualTo("The Lady's Companion");
        assertThat(show.watchCount()).isEqualTo(2);
        assertThat(show.aliases()).containsExactlyInAnyOrder("The Lady's Companion: Lesson 1", "The Lady's Companion: Lesson 2");
    }

    @Test
    void consolidatesEpisodeRecordsWhenOverrideMatchesAlias() {
        TitleNormalizer normalizer = new TitleNormalizer();
        List<WatchEvent> events = List.of(
                normalizer.normalize(WatchEvent.raw("The Lady's Companion: Lesson 1: Never Fall in Love", LocalDateTime.of(2025, 11, 18, 0, 0), "NETFLIX")),
                normalizer.normalize(WatchEvent.raw("The Lady's Companion: Lesson 2: Do Not Tell Lies", LocalDateTime.of(2025, 11, 18, 0, 0), "NETFLIX")));

        // Create an override for the base show title
        MetadataOverride override = new MetadataOverride("The Lady's Companion", "TMDB", "tv", 280890);
        MetadataOverrideStore overrides = MetadataOverrideStore.of(List.of(override));

        List<Show> shows = new ShowBuilderService(new NoOpDiagnosticsCollector(), overrides).build(events);

        // Should consolidate into a single show with the override title
        assertThat(shows).hasSize(1);
        Show show = shows.getFirst();
        assertThat(show.title()).isEqualTo("The Lady's Companion");
        assertThat(show.watchCount()).isEqualTo(2);
    }

    @Test
    void separatesStandaloneSpecialsWhenOverrideTargetsSpecificAlias() {
        TitleNormalizer normalizer = new TitleNormalizer();
        List<WatchEvent> events = List.of(
                normalizer.normalize(WatchEvent.raw("Gabriel Iglesias: Stadium Fluffy", LocalDateTime.of(2024, 1, 1, 0, 0), "NETFLIX")),
                normalizer.normalize(WatchEvent.raw("Gabriel Iglesias: Legend of Fluffy", LocalDateTime.of(2024, 1, 2, 0, 0), "NETFLIX")));

        // Create an override for just one special, treating it as its own entity
        MetadataOverride legendOverride = new MetadataOverride("Gabriel Iglesias: Legend of Fluffy", "TMDB", "movie", 111);
        MetadataOverrideStore overrides = MetadataOverrideStore.of(List.of(legendOverride));

        List<Show> shows = new ShowBuilderService(new NoOpDiagnosticsCollector(), overrides).build(events);

        // Should separate into two shows: one for the grouped "Gabriel Iglesias" (Stadium Fluffy)
        // and one for the specific override (Legend of Fluffy)
        assertThat(shows).hasSize(2);
        
        // Find the show that kept the canonical title (Stadium Fluffy only)
        Show groupedShow = shows.stream().filter(s -> s.title().equals("Gabriel Iglesias")).findFirst().orElseThrow();
        assertThat(groupedShow.watchCount()).isEqualTo(1);
        assertThat(groupedShow.aliases()).containsExactly("Gabriel Iglesias: Stadium Fluffy");
        
        // Find the show that used the override title (Legend of Fluffy only)
        Show standaloneShow = shows.stream().filter(s -> s.title().equals("Gabriel Iglesias: Legend of Fluffy")).findFirst().orElseThrow();
        assertThat(standaloneShow.watchCount()).isEqualTo(1);
        assertThat(standaloneShow.aliases()).containsExactly("Gabriel Iglesias: Legend of Fluffy");
    }
}

package com.watchtracker.service;

import static org.assertj.core.api.Assertions.assertThat;

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
}

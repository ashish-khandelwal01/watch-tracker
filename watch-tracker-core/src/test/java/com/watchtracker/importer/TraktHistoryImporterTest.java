package com.watchtracker.importer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchtracker.model.WatchEvent;
import com.watchtracker.normalizer.TitleNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TraktHistoryImporterTest {

    @TempDir
    Path temp;

    @Test
    void mapsMovieAndEpisodeHistoryRows() throws Exception {
        Path file = temp.resolve("history.json");
        Files.writeString(
                file,
                """
                [
                  {
                    "watched_at": "2024-03-01T20:15:00.000Z",
                    "type": "movie",
                    "movie": { "title": "Inception", "year": 2010 }
                  },
                  {
                    "watched_at": "2024-03-02T21:00:00.000Z",
                    "type": "episode",
                    "episode": { "season": 1, "number": 3, "title": "Chapter Three" },
                    "show": { "title": "Dark" }
                  }
                ]
                """);

        List<WatchEvent> events = new TraktHistoryImporter().parseFile(file);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).rawTitle()).isEqualTo("Inception");
        assertThat(events.get(0).platform()).isEqualTo("TRAKT");
        assertThat(events.get(1).rawTitle()).isEqualTo("Dark: Season 1: Chapter Three");

        WatchEvent normalized = new TitleNormalizer().normalize(events.get(1));
        assertThat(normalized.canonicalShow()).isEqualTo("Dark");
        assertThat(normalized.season()).isEqualTo(1);
        assertThat(normalized.episode()).isEqualTo(3);
    }

    @Test
    void formatEpisodeTitleFallsBackToEpisodeNumber() {
        var mapper = new ObjectMapper();
        var show = mapper.createObjectNode().put("title", "Dark");
        var episode = mapper.createObjectNode().put("season", 2).put("number", 5);

        assertThat(TraktHistoryImporter.formatEpisodeTitle(show, episode))
                .isEqualTo("Dark: Season 2: Episode 5");
    }
}

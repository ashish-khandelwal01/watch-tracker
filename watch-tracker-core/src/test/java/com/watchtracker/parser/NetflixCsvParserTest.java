package com.watchtracker.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.watchtracker.exception.NetflixCsvException;
import com.watchtracker.model.WatchEvent;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the Netflix CSV boundary and its header validation. */
class NetflixCsvParserTest {
    @Test
    void parsesRawEventsWithoutNormalizingTitles() throws URISyntaxException {
        List<WatchEvent> events = new NetflixCsvParser().parse(resource("netflix-history.csv"));

        assertThat(events).hasSize(3);
        assertThat(events.getFirst().rawTitle()).isEqualTo("Money Heist: Part 3: Episode 5");
        assertThat(events.getFirst().canonicalShow()).isNull();
        assertThat(events.getFirst().parsed()).isFalse();
    }

    @Test
    void rejectsMissingFiles() {
        assertThatThrownBy(() -> new NetflixCsvParser().parse(Path.of("missing.csv")))
                .isInstanceOf(NetflixCsvException.class);
    }

    @Test
    void acceptsNetflixUsStyleDates() throws java.io.IOException {
        Path csv = java.nio.file.Files.createTempFile("netflix-history", ".csv");
        java.nio.file.Files.writeString(csv, "Title,Date\nDark: Episode 1,7/19/26\n");

        assertThat(new NetflixCsvParser().parse(csv).getFirst().watchedAt().toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026, 7, 19));
    }

    @Test
    void skipsBlankTitleRowsFoundInExports() throws java.io.IOException {
        Path csv = java.nio.file.Files.createTempFile("netflix-history", ".csv");
        java.nio.file.Files.writeString(csv, "Title,Date\n\" \",7/19/26\nDark: Episode 1,7/18/26\n");

        assertThat(new NetflixCsvParser().parse(csv)).hasSize(1);
    }

    private static Path resource(String name) throws URISyntaxException {
        return Path.of(NetflixCsvParserTest.class.getResource('/' + name).toURI());
    }
}

package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OverrideDataExporterTest {

    @Test
    void extractsOnlyManualOverrideRows() {
        ShowMetadata manual = new ShowMetadata(
                "TMDB",
                280890,
                "The Lady's Companion",
                ShowType.SERIES,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                MatchConfidence.MANUAL_OVERRIDE);
        ShowMetadata exact = new ShowMetadata(
                "TMDB", 1, "Dark", ShowType.SERIES, List.of(), null, null, null, null, null, MatchConfidence.EXACT);
        List<EnrichedShow> enriched = List.of(
                new EnrichedShow(show("The Lady's Companion"), Optional.of(manual), Optional.empty()),
                new EnrichedShow(show("Dark"), Optional.of(exact), Optional.empty()));

        List<MetadataOverride> rows = OverrideDataExporter.extract(enriched);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).title()).isEqualTo("The Lady's Companion");
        assertThat(rows.get(0).providerId()).isEqualTo(280890);
        assertThat(rows.get(0).mediaType()).isEqualTo("tv");
    }

    private static Show show(String title) {
        return new Show(
                UUID.randomUUID(),
                title,
                Set.of(title),
                ShowType.UNKNOWN,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 1, 0, 0),
                null,
                null,
                1);
    }
}

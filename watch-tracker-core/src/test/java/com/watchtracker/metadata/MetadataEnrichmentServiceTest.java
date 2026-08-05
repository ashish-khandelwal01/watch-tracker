package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.exporter.WatchHistoryExporter;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
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
}

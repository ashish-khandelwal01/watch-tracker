package com.watchtracker.web.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.metadata.MatchConfidence;
import com.watchtracker.metadata.ShowMetadata;
import com.watchtracker.metadata.WatchProgress;
import com.watchtracker.model.CompletionStatus;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link EnrichedShowMongoMapper} round-trips {@link EnrichedShow} through a document
 * without needing a real MongoDB instance — the mapper's logic is plain JSON (de)serialization,
 * so a Spring/embedded-Mongo integration test would only add setup cost without exercising
 * anything a plain unit test doesn't already cover.
 */
class EnrichedShowMongoMapperTest {

    private static Show show(UUID id, Set<String> aliases) {
        return new Show(
                id,
                "Dark",
                aliases,
                ShowType.SERIES,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                LocalDateTime.of(2024, 1, 3, 0, 0),
                2,
                4,
                12);
    }

    @Test
    void roundTripsFullEnrichedShow() {
        UUID id = UUID.randomUUID();
        Show show = show(id, Set.of("Dark (2017)"));
        ShowMetadata metadata = new ShowMetadata(
                "TMDB",
                70523,
                "Dark",
                ShowType.SERIES,
                List.of("Sci-Fi", "Mystery"),
                "A time-travel mystery.",
                "/poster.jpg",
                LocalDate.of(2017, 12, 1),
                26,
                true,
                MatchConfidence.EXACT);
        WatchProgress progress = new WatchProgress(ShowType.SERIES, 2, 4, "TMDB", CompletionStatus.IN_PROGRESS);
        EnrichedShow enriched = new EnrichedShow(show, Optional.of(metadata), Optional.of(progress));

        EnrichedShowMongoMapper mapper = new EnrichedShowMongoMapper();
        EnrichedShowDocument document = mapper.toDocument(enriched);
        Optional<EnrichedShow> roundTripped = mapper.toEnrichedShow(document);

        assertThat(document.id()).isEqualTo(id.toString());
        assertThat(document.title()).isEqualTo("Dark");
        assertThat(document.aliases()).containsExactly("Dark (2017)");
        assertThat(document.matchConfidence()).isEqualTo("EXACT");
        assertThat(roundTripped).isPresent();
        assertThat(roundTripped.get()).isEqualTo(enriched);
    }

    @Test
    void roundTripsEnrichedShowWithNoMetadataOrProgress() {
        UUID id = UUID.randomUUID();
        Show show = show(id, Set.of());
        EnrichedShow enriched = new EnrichedShow(show, Optional.empty(), Optional.empty());

        EnrichedShowMongoMapper mapper = new EnrichedShowMongoMapper();
        EnrichedShowDocument document = mapper.toDocument(enriched);

        assertThat(document.matchConfidence()).isNull();
        assertThat(mapper.toEnrichedShow(document)).contains(enriched);
    }

    @Test
    void malformedPayloadIsTreatedAsAbsentRatherThanThrowing() {
        EnrichedShowDocument corrupt = new EnrichedShowDocument(
                UUID.randomUUID().toString(), "Broken", List.of(), null, "{not valid json", Instant.now());

        EnrichedShowMongoMapper mapper = new EnrichedShowMongoMapper();

        assertThat(mapper.toEnrichedShow(corrupt)).isEmpty();
    }
}

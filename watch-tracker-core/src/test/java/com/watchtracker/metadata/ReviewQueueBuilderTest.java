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

/** Verifies which shows {@link ReviewQueueBuilder} flags, and why. */
class ReviewQueueBuilderTest {

    @Test
    void flagsAShowWithNoMetadataAndDoesNotAlsoReportMissingProgressRedundantly() {
        EnrichedShow enriched = new EnrichedShow(show("No Match"), Optional.empty(), Optional.empty());

        List<ReviewItem> queue = ReviewQueueBuilder.build(List.of(enriched));

        assertThat(queue).hasSize(1);
        ReviewItem item = queue.get(0);
        assertThat(item.reasons()).containsExactly(ReviewReason.NO_METADATA_MATCH);
        assertThat(item.currentMatch()).isNull();
    }

    @Test
    void doesNotFlagAShowWithAnExactMatchAndResolvedProgress() {
        ShowMetadata metadata = exactMatch();
        EnrichedShow enriched = new EnrichedShow(
                show("Clean Match"),
                Optional.of(metadata),
                Optional.of(new WatchProgress(ShowType.MOVIE, null, null, "TMDB")));

        List<ReviewItem> queue = ReviewQueueBuilder.build(List.of(enriched));

        assertThat(queue).isEmpty();
    }

    @Test
    void flagsALowConfidenceMatchEvenWhenProgressResolved() {
        ShowMetadata metadata = new ShowMetadata(
                "TMDB",
                42,
                "Some Other Title",
                ShowType.MOVIE,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                MatchConfidence.CLOSEST_RELATED);
        EnrichedShow enriched = new EnrichedShow(
                show("Ambiguous Title"),
                Optional.of(metadata),
                Optional.of(new WatchProgress(ShowType.MOVIE, null, null, "TMDB")));

        List<ReviewItem> queue = ReviewQueueBuilder.build(List.of(enriched));

        assertThat(queue).hasSize(1);
        ReviewItem item = queue.get(0);
        assertThat(item.reasons()).containsExactly(ReviewReason.LOW_CONFIDENCE_MATCH);
        assertThat(item.currentMatch()).isNotNull();
        assertThat(item.currentMatch().provider()).isEqualTo("TMDB");
        assertThat(item.currentMatch().providerId()).isEqualTo(42);
        assertThat(item.currentMatch().confidence()).isEqualTo(MatchConfidence.CLOSEST_RELATED);
    }

    @Test
    void flagsAnExactMatchWithNoResolvedProgress() {
        ShowMetadata metadata = exactMatch();
        EnrichedShow enriched = new EnrichedShow(show("Unresolved Episodes"), Optional.of(metadata), Optional.empty());

        List<ReviewItem> queue = ReviewQueueBuilder.build(List.of(enriched));

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).reasons()).containsExactly(ReviewReason.NO_PROGRESS_RESOLVED);
    }

    @Test
    void canReportBothLowConfidenceAndNoProgressForTheSameShow() {
        ShowMetadata metadata = new ShowMetadata(
                "TVMAZE",
                7,
                "Guessed Title",
                ShowType.SERIES,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                MatchConfidence.FIRST_RESULT_FALLBACK);
        EnrichedShow enriched = new EnrichedShow(show("Doubly Uncertain"), Optional.of(metadata), Optional.empty());

        List<ReviewItem> queue = ReviewQueueBuilder.build(List.of(enriched));

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).reasons())
                .containsExactly(ReviewReason.LOW_CONFIDENCE_MATCH, ReviewReason.NO_PROGRESS_RESOLVED);
    }

    private static ShowMetadata exactMatch() {
        return new ShowMetadata(
                "TMDB", 1, "Clean Match", ShowType.MOVIE, List.of(), null, null, null, null, null, MatchConfidence.EXACT);
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

package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.watchtracker.model.ShowType;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies {@link ShowMetadata}'s total-episode-count and production-ended fields. */
class ShowMetadataTest {
    @Test
    void theBackwardsCompatibleConstructorDefaultsTotalEpisodesAndProductionEndedToNull() {
        ShowMetadata metadata =
                new ShowMetadata("TMDB", 1, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null);

        assertThat(metadata.totalEpisodes()).isNull();
        assertThat(metadata.productionEnded()).isNull();
    }

    @Test
    void theCanonicalConstructorAcceptsExplicitTotalEpisodesAndProductionEnded() {
        ShowMetadata metadata =
                new ShowMetadata("TMDB", 1, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null, 26, true);

        assertThat(metadata.totalEpisodes()).isEqualTo(26);
        assertThat(metadata.productionEnded()).isTrue();
    }

    @Test
    void rejectsANegativeTotalEpisodeCount() {
        assertThatThrownBy(() -> new ShowMetadata(
                "TMDB", 1, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null, -1, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalEpisodes");
    }

    @Test
    void allowsAZeroTotalEpisodeCount() {
        // A just-announced show with no released episodes yet is a legitimate, if unusual, case;
        // MetadataEnrichmentService's completion-status logic treats zero as "no basis to compare
        // against" (UNKNOWN), the same as null, rather than this record rejecting it outright.
        ShowMetadata metadata =
                new ShowMetadata("TMDB", 1, "Unannounced", ShowType.SERIES, List.of(), null, null, null, 0, false);

        assertThat(metadata.totalEpisodes()).isZero();
    }
}
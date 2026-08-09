package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Verifies how {@link TmdbMetadataProvider} disambiguates between /search/multi candidates. */
class TmdbMetadataProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void prefersAnExactTitleMatchOverAMorePopularUnrelatedResultRankedFirst() throws Exception {
        // TMDb ranks by popularity: the long-running "The Ultimate Fighter" franchise outranks the
        // actual "Fighter" film in a raw /search/multi response, even though it's the wrong result.
        JsonNode results = readResults(
                """
                [
                    {"media_type": "tv", "id": 1, "name": "The Ultimate Fighter"},
                    {"media_type": "movie", "id": 2, "title": "Fighter"}
                ]
                """);

        JsonNode selected = TmdbMetadataProvider.selectBestMatch(results, "Fighter");

        assertThat(selected.path("id").asLong()).isEqualTo(2);
        assertThat(selected.path("title").asText()).isEqualTo("Fighter");
    }

    @Test
    void exactMatchComparisonIgnoresCaseAndPunctuation() throws Exception {
        JsonNode results = readResults(
                """
                [
                    {"media_type": "tv", "id": 1, "name": "Totally Unrelated Show"},
                    {"media_type": "movie", "id": 2, "title": "Mr. Iglesias!"}
                ]
                """);

        JsonNode selected = TmdbMetadataProvider.selectBestMatch(results, "mr iglesias");

        assertThat(selected.path("id").asLong()).isEqualTo(2);
    }

    @Test
    void fallsBackToTheFirstCandidateWhenNoExactMatchExists() throws Exception {
        // Preserves the previous behavior for titles where TMDb's own title legitimately differs
        // from the query (alternate spelling, subtitle, re-release naming, etc.).
        JsonNode results = readResults(
                """
                [
                    {"media_type": "person", "id": 9, "name": "Some Actor"},
                    {"media_type": "tv", "id": 5, "name": "A Somewhat Different Title"},
                    {"media_type": "movie", "id": 6, "title": "Another Candidate"}
                ]
                """);

        JsonNode selected = TmdbMetadataProvider.selectBestMatch(results, "The Original Query Title");

        assertThat(selected.path("id").asLong()).isEqualTo(5);
    }

    @Test
    void skipsNonTvMovieResultsAndInvalidIdsEntirely() throws Exception {
        JsonNode results = readResults(
                """
                [
                    {"media_type": "person", "id": 1, "name": "Fighter"},
                    {"media_type": "tv", "id": 0, "name": "Fighter"},
                    {"media_type": "movie", "id": 3, "title": "Fighter"}
                ]
                """);

        JsonNode selected = TmdbMetadataProvider.selectBestMatch(results, "Fighter");

        assertThat(selected.path("id").asLong()).isEqualTo(3);
    }

    @Test
    void returnsNullWhenResultsAreEmpty() throws Exception {
        JsonNode results = readResults("[]");

        JsonNode selected = TmdbMetadataProvider.selectBestMatch(results, "Anything");

        assertThat(selected).isNull();
    }

    @Test
    void mapsTotalEpisodesAndEndedStatusForATvSeries() throws Exception {
        JsonNode details = readResults(
                """
                {
                    "id": 70523,
                    "name": "Dark",
                    "genres": [{"name": "Drama"}],
                    "number_of_episodes": 26,
                    "status": "Ended"
                }
                """);

        ShowMetadata metadata = TmdbMetadataProvider.toMetadata(details, "tv");

        assertThat(metadata.totalEpisodes()).isEqualTo(26);
        assertThat(metadata.productionEnded()).isTrue();
    }

    @Test
    void mapsStillAiringSeriesAsNotEnded() throws Exception {
        JsonNode details = readResults(
                """
                {
                    "id": 66732,
                    "name": "Stranger Things",
                    "genres": [{"name": "Drama"}],
                    "number_of_episodes": 42,
                    "status": "Returning Series"
                }
                """);

        ShowMetadata metadata = TmdbMetadataProvider.toMetadata(details, "tv");

        assertThat(metadata.totalEpisodes()).isEqualTo(42);
        assertThat(metadata.productionEnded()).isFalse();
    }

    @Test
    void treatsCanceledAsEndedJustLikeEnded() throws Exception {
        JsonNode details = readResults(
                """
                {
                    "id": 1,
                    "name": "Some Show",
                    "genres": [],
                    "number_of_episodes": 8,
                    "status": "Canceled"
                }
                """);

        ShowMetadata metadata = TmdbMetadataProvider.toMetadata(details, "tv");

        assertThat(metadata.productionEnded()).isTrue();
    }

    @Test
    void leavesTotalEpisodesAndProductionEndedNullWhenAbsentFromTheResponse() throws Exception {
        JsonNode details = readResults(
                """
                {
                    "id": 70523,
                    "name": "Dark",
                    "genres": [{"name": "Drama"}]
                }
                """);

        ShowMetadata metadata = TmdbMetadataProvider.toMetadata(details, "tv");

        assertThat(metadata.totalEpisodes()).isNull();
        assertThat(metadata.productionEnded()).isNull();
    }

    @Test
    void leavesTotalEpisodesAndProductionEndedNullForMovies() throws Exception {
        JsonNode details = readResults(
                """
                {
                    "id": 27205,
                    "title": "Inception",
                    "genres": [{"name": "Sci-Fi"}],
                    "number_of_episodes": 999,
                    "status": "Released"
                }
                """);

        ShowMetadata metadata = TmdbMetadataProvider.toMetadata(details, "movie");

        // number_of_episodes/status are TV-only TMDb fields; even if a movie response happened to
        // carry stray values under those keys, a movie has no episode count to report.
        assertThat(metadata.totalEpisodes()).isNull();
        assertThat(metadata.productionEnded()).isNull();
        assertThat(metadata.type()).isEqualTo(com.watchtracker.model.ShowType.MOVIE);
    }

    private JsonNode readResults(String json) throws Exception {
        return mapper.readTree(json);
    }
}
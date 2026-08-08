package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Verifies how {@link TvMazeMetadataProvider} disambiguates between /search/shows candidates. */
class TvMazeMetadataProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void prefersAnExactTitleMatchOverAHigherRankedUnrelatedResult() throws Exception {
        // TVMaze's own relevance ranking can still put a similarly-named but wrong show first.
        JsonNode results = readResults(
                """
                [
                    {"score": 9.1, "show": {"id": 1, "name": "The Ultimate Fighter"}},
                    {"score": 4.3, "show": {"id": 2, "name": "Fighter"}}
                ]
                """);

        JsonNode selected = TvMazeMetadataProvider.selectBestMatch(results, "Fighter");

        assertThat(selected.path("id").asLong()).isEqualTo(2);
        assertThat(selected.path("name").asText()).isEqualTo("Fighter");
    }

    @Test
    void exactMatchComparisonIgnoresCaseAndPunctuation() throws Exception {
        JsonNode results = readResults(
                """
                [
                    {"score": 8.0, "show": {"id": 1, "name": "Totally Unrelated Show"}},
                    {"score": 3.0, "show": {"id": 2, "name": "Mr. Iglesias!"}}
                ]
                """);

        JsonNode selected = TvMazeMetadataProvider.selectBestMatch(results, "mr iglesias");

        assertThat(selected.path("id").asLong()).isEqualTo(2);
    }

    @Test
    void fallsBackToTheTopRankedCandidateWhenNoExactMatchExists() throws Exception {
        // Preserves the previous behavior for titles where TVMaze's own name legitimately differs
        // from the query (alternate spelling, subtitle, re-release naming, etc.).
        JsonNode results = readResults(
                """
                [
                    {"score": 7.0, "show": {"id": 5, "name": "A Somewhat Different Title"}},
                    {"score": 6.0, "show": {"id": 6, "name": "Another Candidate"}}
                ]
                """);

        JsonNode selected = TvMazeMetadataProvider.selectBestMatch(results, "The Original Query Title");

        assertThat(selected.path("id").asLong()).isEqualTo(5);
    }

    @Test
    void returnsNullWhenResultsAreEmpty() throws Exception {
        JsonNode results = readResults("[]");

        JsonNode selected = TvMazeMetadataProvider.selectBestMatch(results, "Anything");

        assertThat(selected).isNull();
    }

    @Test
    void returnsNullWhenResultsAreNotAnArray() throws Exception {
        // The 404-mapped empty-array response from request() is the normal "not found" shape, but
        // this guards the method itself against any non-array input.
        JsonNode results = readResults("{}");

        JsonNode selected = TvMazeMetadataProvider.selectBestMatch(results, "Anything");

        assertThat(selected).isNull();
    }

    private JsonNode readResults(String json) throws Exception {
        return mapper.readTree(json);
    }
}

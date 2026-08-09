package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Verifies how {@link TvMazeMetadataProvider} disambiguates between /search/shows candidates. */
class TvMazeMetadataProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

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

    @Test
    void mapsEndedStatusAndThePassedInTotalEpisodeCount() throws Exception {
        JsonNode show = readResults(
                """
                {
                    "id": 1,
                    "name": "Breaking Bad",
                    "genres": ["Drama"],
                    "status": "Ended"
                }
                """);

        ShowMetadata metadata = TvMazeMetadataProvider.toMetadata(show, 62);

        assertThat(metadata.totalEpisodes()).isEqualTo(62);
        assertThat(metadata.productionEnded()).isTrue();
    }

    @Test
    void mapsRunningStatusAsNotEnded() throws Exception {
        JsonNode show = readResults(
                """
                {
                    "id": 2,
                    "name": "Some Ongoing Show",
                    "genres": [],
                    "status": "Running"
                }
                """);

        ShowMetadata metadata = TvMazeMetadataProvider.toMetadata(show, 10);

        assertThat(metadata.productionEnded()).isFalse();
    }

    @Test
    void treatsAmbiguousStatusesConservativelyAsNotEnded() throws Exception {
        // "To Be Determined" and "In Development" are neither clearly finished nor clearly airing;
        // only an explicit "Ended" should ever report productionEnded as true.
        JsonNode show = readResults(
                """
                {
                    "id": 3,
                    "name": "Unannounced Show",
                    "genres": [],
                    "status": "To Be Determined"
                }
                """);

        ShowMetadata metadata = TvMazeMetadataProvider.toMetadata(show, null);

        assertThat(metadata.productionEnded()).isFalse();
    }

    @Test
    void leavesProductionEndedNullWhenStatusIsAbsent() throws Exception {
        JsonNode show = readResults(
                """
                {
                    "id": 4,
                    "name": "No Status Show",
                    "genres": []
                }
                """);

        ShowMetadata metadata = TvMazeMetadataProvider.toMetadata(show, null);

        assertThat(metadata.productionEnded()).isNull();
        assertThat(metadata.totalEpisodes()).isNull();
    }

    @Test
    @Timeout(10)
    void requestTimesOutInsteadOfHangingWhenTheServerNeverResponds() throws Exception {
        // Regression test: java.net.http.HttpClient has no default timeout, so a server that
        // accepts the connection but never sends a response previously blocked indefinitely — a
        // single stalled request could stall an entire import for hours. This proves the request
        // actually times out in bounded time rather than merely asserting a constant exists.
        CountDownLatch requestReceived = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/search/shows", exchange -> {
            requestReceived.countDown();
            // Deliberately never respond and never close the exchange, simulating a hung connection.
        });
        server.start();
        String apiRoot = "http://localhost:" + server.getAddress().getPort();
        TvMazeMetadataProvider provider = new TvMazeMetadataProvider(
                HttpClient.newHttpClient(), mapper, apiRoot, Duration.ofMillis(500));

        Instant start = Instant.now();
        assertThatThrownBy(() -> provider.findByTitle("Anything")).isInstanceOf(IllegalStateException.class);
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(requestReceived.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // Well under the old "forever" and comfortably above the 500ms timeout to avoid flakiness.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    @Timeout(10)
    void findByTitleDoesNotFailWhenTheEpisodeCountFetchTimesOutAfterAMatchIsAlreadyFound() throws Exception {
        // The show search itself succeeds; only the bonus episode-guide fetch used for
        // totalEpisodes hangs. That must degrade to a null total, not crash the whole match --
        // completion status just becomes UNKNOWN for this show rather than the run failing.
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/search/shows", exchange -> {
            byte[] body = ("[{\"score\": 9.0, \"show\": {\"id\": 1, \"name\": \"Anything\", \"genres\": [], "
                    + "\"status\": \"Ended\"}}]")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/shows/1/episodes", exchange -> {
            // Never respond, simulating a hung connection on this specific endpoint only.
        });
        server.start();
        String apiRoot = "http://localhost:" + server.getAddress().getPort();
        TvMazeMetadataProvider provider = new TvMazeMetadataProvider(
                HttpClient.newHttpClient(), mapper, apiRoot, Duration.ofMillis(500));

        Optional<ShowMetadata> result = provider.findByTitle("Anything");

        assertThat(result).isPresent();
        assertThat(result.get().totalEpisodes()).isNull();
        assertThat(result.get().productionEnded()).isTrue();
    }

    private JsonNode readResults(String json) throws Exception {
        return mapper.readTree(json);
    }
}
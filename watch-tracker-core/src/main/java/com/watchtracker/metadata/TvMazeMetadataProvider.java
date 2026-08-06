package com.watchtracker.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchtracker.model.ShowType;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** TVMaze provider for TV-series metadata; it uses TVMaze's public search API. */
public final class TvMazeMetadataProvider implements MetadataProvider {
    private static final String API_ROOT = "https://api.tvmaze.com";
    private final HttpClient client;
    private final ObjectMapper mapper;

    // Per-instance lookup caches so a run resolving many named episodes for the same show only
    // performs one show search and one per-season episode-guide fetch, instead of repeating both
    // HTTP calls for every episode title being resolved.
    private final Map<String, Long> showIdByTitle = new ConcurrentHashMap<>();
    private final Map<Long, List<JsonNode>> episodesByShowId = new ConcurrentHashMap<>();

    /** Creates a provider using Java's standard HTTP client. */
    public TvMazeMetadataProvider() {
        this(HttpClient.newHttpClient(), new ObjectMapper());
    }

    /**
     * Creates a provider with injectable collaborators for deterministic integration tests.
     *
     * @param client HTTP client used for TVMaze requests
     * @param mapper JSON mapper used to decode TVMaze responses
     */
    TvMazeMetadataProvider(HttpClient client, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Finds the best TVMaze show result for a canonical title.
     *
     * @param title canonical title to search
     * @return TVMaze metadata when a show is found
     */
    @Override
    public Optional<ShowMetadata> findByTitle(String title) {
        JsonNode results = request("/search/shows?q=" + encode(title));
        if (!results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toMetadata(results.get(0).path("show")));
    }

    /**
     * Resolves a named Netflix episode against TVMaze's complete episode guide.
     *
     * @param showTitle canonical show title
     * @param season Netflix part or season number
     * @param episodeTitle Netflix episode title
     * @return canonical TVMaze episode position when its title matches
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
        long showId = showId(showTitle);
        if (showId < 1) {
            return Optional.empty();
        }
        for (JsonNode episode : episodesForShow(showId)) {
            if (episode.path("season").asInt() == season && sameTitle(episode.path("name").asText(), episodeTitle)) {
                int episodeNumber = episode.path("number").asInt();
                return episodeNumber > 0 ? Optional.of(new WatchProgress(season, episodeNumber, "TVMAZE")) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Resolves and caches the TVMaze show id for a title, fetching it at most once per title. */
    private long showId(String showTitle) {
        return showIdByTitle.computeIfAbsent(showTitle, title -> {
            JsonNode results = request("/search/shows?q=" + encode(title));
            return results.isArray() && !results.isEmpty()
                    ? results.get(0).path("show").path("id").asLong()
                    : -1L;
        });
    }

    /** Resolves and caches a show's complete episode guide, fetching it at most once per show. */
    private List<JsonNode> episodesForShow(long showId) {
        return episodesByShowId.computeIfAbsent(showId, id -> {
            List<JsonNode> all = new ArrayList<>();
            request("/shows/" + id + "/episodes").forEach(all::add);
            return all;
        });
    }

    /** Executes a TVMaze search request. */
    private JsonNode request(String endpoint) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_ROOT + endpoint))
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return mapper.createArrayNode();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("TVMaze request failed with HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to call TVMaze", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TVMaze request was interrupted", exception);
        }
    }

    /** Maps TVMaze's nested search result to the provider-neutral metadata model. */
    private static ShowMetadata toMetadata(JsonNode show) {
        List<String> genres = new ArrayList<>();
        show.path("genres").forEach(genre -> genres.add(genre.asText()));
        return new ShowMetadata(
                "TVMAZE",
                show.path("id").asLong(),
                show.path("name").asText(),
                ShowType.SERIES,
                genres,
                stripHtml(nullable(show, "summary")),
                nullable(show.path("image"), "original"),
                date(nullable(show, "premiered")));
    }

    /** Encodes a title as a query-string value. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Extracts an optional string field. */
    private static String nullable(JsonNode node, String field) {
        return node.path(field).isNull() ? null : node.path(field).asText(null);
    }

    /** Compares episode titles while tolerating case and punctuation differences. */
    private static boolean sameTitle(String first, String second) {
        return normalizeTitle(first).equals(normalizeTitle(second));
    }

    /** Produces a comparison key for external episode titles. */
    private static String normalizeTitle(String title) {
        return title == null ? "" : title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{Alnum}]", "");
    }

    /** Removes simple markup that TVMaze returns in programme summaries. */
    private static String stripHtml(String value) {
        return value == null ? null : value.replaceAll("<[^>]*>", "").trim();
    }

    /** Parses a provider date while treating absent or invalid data as unavailable. */
    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }
}

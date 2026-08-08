package com.watchtracker.metadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
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
        this(HttpClient.newHttpClient(), new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
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
        JsonNode selected = selectBestMatch(results, title);
        return selected == null ? Optional.empty() : Optional.of(toMetadata(selected));
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
        List<JsonNode> inSeason = episodesForShow(showId).stream()
                .filter(episode -> episode.path("season").asInt() == season)
                .toList();
        return matchByTitle(inSeason, episodeTitle);
    }

    /**
     * Resolves a named Netflix episode anywhere in TVMaze's episode guide, ignoring season
     * boundaries. Only attempted as a last resort after season-scoped resolution has failed on
     * every configured provider, since a mismatched season count means the two providers'
     * season/episode groupings are known to disagree for this show.
     *
     * @param showTitle canonical show title
     * @param episodeTitle Netflix episode title
     * @return canonical TVMaze episode position when its title is found in any season
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
        long showId = showId(showTitle);
        if (showId < 1) {
            return Optional.empty();
        }
        return matchByTitle(episodesForShow(showId), episodeTitle);
    }

    /** Finds the first episode among the candidates whose title matches, using its own position. */
    private static Optional<WatchProgress> matchByTitle(List<JsonNode> candidates, String episodeTitle) {
        for (JsonNode episode : candidates) {
            if (sameTitle(episode.path("name").asText(), episodeTitle)) {
                int season = episode.path("season").asInt();
                int episodeNumber = episode.path("number").asInt();
                if (season > 0 && episodeNumber > 0) {
                    return Optional.of(new WatchProgress(season, episodeNumber, "TVMAZE"));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Chooses the best {@code /search/shows} candidate for a title.
     *
     * <p>TVMaze ranks results by its own relevance score, but a close-but-wrong show with a similar
     * name can still rank above the actual match — the same class of problem as TMDb's
     * popularity-ranked multi-search (see {@code TmdbMetadataProvider.selectBestMatch}), just via a
     * different ranking signal. Blindly taking the first hit risks the same kind of mismatch: a
     * well-known show with a similar name outranking an obscure but correct one.
     *
     * <p>This prefers the first result whose own name exactly matches the query
     * (case/punctuation-insensitive, via {@link #sameTitle}), scanning every candidate rather than
     * stopping at the first hit. Only when no exact match exists anywhere does it fall back to
     * TVMaze's own top-ranked result, preserving the previous behavior for titles where TVMaze's own
     * name legitimately differs (alternate spelling, subtitle, re-release naming, etc.).
     *
     * @param results the array response from {@code /search/shows}, each entry wrapping a show under
     *     a {@code "show"} key
     * @param queryTitle the title that was searched for
     * @return the selected show node, unwrapped from its {@code "show"} envelope, or {@code null}
     *     when results are empty
     */
    static JsonNode selectBestMatch(JsonNode results, String queryTitle) {
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        JsonNode fallback = null;
        for (JsonNode result : results) {
            JsonNode show = result.path("show");
            if (fallback == null) {
                fallback = show;
            }
            if (sameTitle(show.path("name").asText(), queryTitle)) {
                return show;
            }
        }
        return fallback;
    }

    /** Resolves and caches the TVMaze show id for a title, fetching it at most once per title. */
    private long showId(String showTitle) {
        return showIdByTitle.computeIfAbsent(showTitle, title -> {
            JsonNode results = request("/search/shows?q=" + encode(title));
            JsonNode selected = selectBestMatch(results, title);
            return selected == null ? -1L : selected.path("id").asLong();
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
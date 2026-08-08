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

/** TMDb v3 provider using the official API Read Access Token bearer authentication. */
public final class TmdbMetadataProvider implements MetadataProvider {
    private static final String API_ROOT = "https://api.themoviedb.org/3";
    private final HttpClient client;
    private final String token;
    private final ObjectMapper mapper;

    // Per-instance lookup caches so a run resolving many named episodes for the same show/season
    // only performs one series search and one per-season episode fetch, instead of repeating both
    // HTTP calls for every episode title being resolved.
    private final Map<String, Long> seriesIdByTitle = new ConcurrentHashMap<>();
    private final Map<String, List<JsonNode>> episodesBySeasonKey = new ConcurrentHashMap<>();
    private final Map<Long, Integer> seasonCountBySeriesId = new ConcurrentHashMap<>();

    /**
     * Creates a provider using Java's standard HTTP client.
     *
     * @param apiReadAccessToken TMDb API Read Access Token
     */
    public TmdbMetadataProvider(String apiReadAccessToken) {
        this(HttpClient.newHttpClient(), apiReadAccessToken, new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    /**
     * Creates a provider with injectable collaborators for deterministic integration tests.
     *
     * @param client HTTP client used for TMDb requests
     * @param apiReadAccessToken TMDb API Read Access Token
     * @param mapper JSON mapper used to decode TMDb responses
     */
    TmdbMetadataProvider(HttpClient client, String apiReadAccessToken, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.token = requireToken(apiReadAccessToken);
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Searches TV and movie results, then retrieves the selected result's full details.
     *
     * @param title canonical title to search
     * @return TMDb metadata for the best-matching TV or movie result
     */
    @Override
    public Optional<ShowMetadata> findByTitle(String title) {
        JsonNode results = request("/search/multi?include_adult=false&language=en-US&query=" + encode(title))
                .path("results");
        JsonNode selected = selectBestMatch(results, title);
        if (selected == null) {
            return Optional.empty();
        }
        String mediaType = selected.path("media_type").asText();
        long id = selected.path("id").asLong();
        return Optional.of(toMetadata(request('/' + mediaType + '/' + id), mediaType));
    }

    /**
     * Chooses the best {@code /search/multi} candidate for a title.
     *
     * <p>TMDb ranks multi-search results by popularity, not by how closely each result's own title
     * matches the query. Blindly taking the first TV/movie hit means a more famous, similarly-named
     * result can silently outrank the actual match — for example, a query for "Fighter" (an obscure
     * film) can be outranked by "The Ultimate Fighter" (a long-running, far more popular reality
     * franchise), producing plausible-looking but entirely wrong metadata.
     *
     * <p>This prefers the first result whose own title exactly matches the query
     * (case/punctuation-insensitive, via {@link #sameTitle}), scanning every candidate rather than
     * stopping at the first TV/movie hit. Only when no exact match exists anywhere in the results
     * does it fall back to the first TV/movie hit, preserving the previous behavior for titles where
     * TMDb's own title legitimately differs (alternate spelling, subtitle, re-release naming, etc.).
     *
     * @param results the {@code results} array from a {@code /search/multi} response
     * @param queryTitle the title that was searched for
     * @return the selected result node, or {@code null} if no TV/movie candidate exists
     */
    static JsonNode selectBestMatch(JsonNode results, String queryTitle) {
        JsonNode fallback = null;
        for (JsonNode result : results) {
            String mediaType = result.path("media_type").asText();
            if (!"tv".equals(mediaType) && !"movie".equals(mediaType)) {
                continue;
            }
            if (result.path("id").asLong() <= 0) {
                continue;
            }
            if (fallback == null) {
                fallback = result;
            }
            String resultTitle = "tv".equals(mediaType) ? result.path("name").asText() : result.path("title").asText();
            if (sameTitle(resultTitle, queryTitle)) {
                return result;
            }
        }
        return fallback;
    }

    /**
     * Resolves a named Netflix episode against TMDb's episode guide for the specified season.
     *
     * @param showTitle canonical show title
     * @param season Netflix part or season number
     * @param episodeTitle Netflix episode title
     * @return canonical TMDb episode position when its title matches
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(String showTitle, int season, String episodeTitle) {
        long seriesId = seriesId(showTitle);
        if (seriesId < 1) {
            return Optional.empty();
        }
        for (JsonNode episode : episodesForSeason(seriesId, season)) {
            if (sameTitle(episode.path("name").asText(), episodeTitle)) {
                int episodeNumber = episode.path("episode_number").asInt();
                return episodeNumber > 0
                        ? Optional.of(new WatchProgress(season, episodeNumber, "TMDB"))
                        : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** Resolves and caches the TMDb series id for a title, fetching it at most once per title. */
    private long seriesId(String showTitle) {
        return seriesIdByTitle.computeIfAbsent(showTitle, title -> {
            JsonNode results = request("/search/tv?include_adult=false&language=en-US&query=" + encode(title))
                    .path("results");
            return results.isEmpty() ? -1L : results.get(0).path("id").asLong();
        });
    }

    /** Resolves and caches one season's episode list, fetching it at most once per show and season. */
    private List<JsonNode> episodesForSeason(long seriesId, int season) {
        return episodesBySeasonKey.computeIfAbsent(seriesId + ":" + season, key -> {
            List<JsonNode> episodes = new ArrayList<>();
            request("/tv/" + seriesId + "/season/" + season).path("episodes").forEach(episodes::add);
            return episodes;
        });
    }

    /**
     * Resolves a named Netflix episode anywhere in TMDb's episode guide, ignoring season
     * boundaries. Only attempted as a last resort after season-scoped resolution has failed on
     * every configured provider, since a mismatched season count means the two providers'
     * season/episode groupings are known to disagree for this show.
     *
     * @param showTitle canonical show title
     * @param episodeTitle Netflix episode title
     * @return canonical TMDb episode position when its title is found in any season
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(String showTitle, String episodeTitle) {
        long seriesId = seriesId(showTitle);
        if (seriesId < 1) {
            return Optional.empty();
        }
        for (int season = 1; season <= seasonCount(seriesId); season++) {
            for (JsonNode episode : episodesForSeason(seriesId, season)) {
                if (sameTitle(episode.path("name").asText(), episodeTitle)) {
                    int resolvedSeason = episode.path("season_number").asInt();
                    int episodeNumber = episode.path("episode_number").asInt();
                    if (resolvedSeason > 0 && episodeNumber > 0) {
                        return Optional.of(new WatchProgress(resolvedSeason, episodeNumber, "TMDB"));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Resolves and caches a series' season count, fetching series details at most once per series. */
    private int seasonCount(long seriesId) {
        return seasonCountBySeriesId.computeIfAbsent(
                seriesId, id -> request("/tv/" + id).path("number_of_seasons").asInt());
    }

    /** Executes an authenticated TMDb request. */
    private JsonNode request(String endpoint) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_ROOT + endpoint))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                // A missing season or series is an expected outcome, not a failure: providers
                // sometimes group a source platform's containers differently (for example, five
                // Netflix "Parts" merged into three TMDb seasons), so season 4 or 5 legitimately
                // does not exist here. Treat it like TVMaze's empty-array response so the caller
                // sees an ordinary miss and the provider fallback chain can proceed.
                return mapper.createArrayNode();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("TMDb request failed with HTTP " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to call TMDb", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TMDb request was interrupted", exception);
        }
    }

    /** Maps a TMDb details document to the provider-neutral metadata model. */
    private static ShowMetadata toMetadata(JsonNode details, String mediaType) {
        List<String> genres = new ArrayList<>();
        details.path("genres").forEach(genre -> genres.add(genre.path("name").asText()));
        String title = "tv".equals(mediaType)
                ? details.path("name").asText()
                : details.path("title").asText();
        String date = "tv".equals(mediaType)
                ? details.path("first_air_date").asText()
                : details.path("release_date").asText();
        return new ShowMetadata(
                "TMDB",
                details.path("id").asLong(),
                title,
                "tv".equals(mediaType) ? ShowType.SERIES : ShowType.MOVIE,
                genres,
                nullable(details, "overview"),
                nullable(details, "poster_path"),
                date(date));
    }

    /** Encodes one value for a TMDb query string. */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Validates and trims an API token. */
    private static String requireToken(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("TMDb API Read Access Token must not be blank");
        }
        return value.trim();
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
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
import java.time.Duration;
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
    private static final String DEFAULT_API_ROOT = "https://api.tvmaze.com";
    // Without these, a stalled connection or an unresponsive server (including a soft rate-limit
    // that never sends a response instead of returning 429) blocks java.net.http.HttpClient
    // indefinitely — there is no default timeout. A single hung request can then stall the entire
    // synchronous, single-threaded import for hours with no way to recover.
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final String apiRoot;
    private final Duration requestTimeout;

    // Per-instance lookup caches so a run resolving many named episodes for the same show only
    // performs one show search and one per-season episode-guide fetch, instead of repeating both
    // HTTP calls for every episode title being resolved.
    private final Map<String, Long> showIdByTitle = new ConcurrentHashMap<>();
    private final Map<Long, List<JsonNode>> episodesByShowId = new ConcurrentHashMap<>();

    /** Creates a provider using Java's standard HTTP client. */
    public TvMazeMetadataProvider() {
        this(
                HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
                new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                DEFAULT_API_ROOT,
                DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Creates a provider with injectable collaborators for deterministic integration tests.
     *
     * @param client HTTP client used for TVMaze requests
     * @param mapper JSON mapper used to decode TVMaze responses
     */
    TvMazeMetadataProvider(HttpClient client, ObjectMapper mapper) {
        this(client, mapper, DEFAULT_API_ROOT, DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * Creates a provider with every collaborator injectable, including the API root and per-request
     * timeout, so tests can point at a local stub server and use a short timeout instead of waiting
     * out the real 30-second default.
     *
     * @param client HTTP client used for TVMaze requests
     * @param mapper JSON mapper used to decode TVMaze responses
     * @param apiRoot base URL for TVMaze requests
     * @param requestTimeout per-request timeout
     */
    TvMazeMetadataProvider(HttpClient client, ObjectMapper mapper, String apiRoot, Duration requestTimeout) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.apiRoot = Objects.requireNonNull(apiRoot, "apiRoot must not be null");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
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
        if (selected == null) {
            return Optional.empty();
        }
        long id = selected.path("id").asLong();
        // TVMaze's search result doesn't carry a total episode count directly (unlike TMDb's
        // /tv/{id} response), so this costs an extra request here — but it's cached by
        // episodesForShow, so a later named-episode resolution for the same show reuses it instead
        // of fetching the guide twice. This is best-effort: it's purely for completion-status data,
        // so a failure here (timeout, rate limit, transient error) must not take down the whole
        // metadata match, which has already succeeded by this point. Worst case, totalEpisodes
        // stays null and completion status is reported as UNKNOWN for this show.
        Integer totalEpisodes = id > 0 ? totalEpisodesOrNull(id) : null;
        ShowMetadata metadata = toMetadata(selected, totalEpisodes);
        rememberSeriesMapping(title, metadata.providerId());
        if (metadata.title() != null && !metadata.title().isBlank() && !metadata.title().equals(title)) {
            rememberSeriesMapping(metadata.title(), metadata.providerId());
        }
        return Optional.of(metadata);
    }

    /** Fetches a show's total episode count, degrading to {@code null} on any failure. */
    private Integer totalEpisodesOrNull(long id) {
        try {
            return episodesForShow(id).size();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Seeds the title → TVMaze show-id map used by title-based episode resolution.
     *
     * @param title title key to associate
     * @param providerId TVMaze show id
     */
    @Override
    public void rememberSeriesMapping(String title, long providerId) {
        if (title == null || title.isBlank() || providerId < 1) {
            return;
        }
        showIdByTitle.put(title.trim(), providerId);
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
        return resolveEpisode(showId, season, episodeTitle);
    }

    /**
     * Resolves a named episode against a TVMaze show already identified by id.
     *
     * @param providerId TVMaze show id
     * @param season Netflix part or season number
     * @param episodeTitle Netflix episode title
     * @return canonical TVMaze episode position when its title matches
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(long providerId, int season, String episodeTitle) {
        if (providerId < 1 || episodeTitle == null || episodeTitle.isBlank()) {
            return Optional.empty();
        }
        List<JsonNode> inSeason = episodesForShow(providerId).stream()
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
        return resolveEpisodeAnySeason(showId, episodeTitle);
    }

    /**
     * Resolves a named episode anywhere in a TVMaze show already identified by id.
     *
     * @param providerId TVMaze show id
     * @param episodeTitle Netflix episode title
     * @return canonical TVMaze episode position when its title is found in any season
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(long providerId, String episodeTitle) {
        if (providerId < 1 || episodeTitle == null || episodeTitle.isBlank()) {
            return Optional.empty();
        }
        return matchByTitle(episodesForShow(providerId), episodeTitle);
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
            long id = searchShowId(title);
            if (id > 0) {
                return id;
            }
            String cleaned = stripLimitedSeriesSuffix(title);
            if (!cleaned.equals(title) && !cleaned.isBlank()) {
                return searchShowId(cleaned);
            }
            return -1L;
        });
    }

    private long searchShowId(String title) {
        JsonNode results = request("/search/shows?q=" + encode(title));
        JsonNode selected = selectBestMatch(results, title);
        return selected == null ? -1L : selected.path("id").asLong();
    }

    private static final java.util.regex.Pattern LIMITED_SERIES_TRAILING =
            java.util.regex.Pattern.compile("(?i)\\s*:?\\s*limited series\\b\\s*$");

    private static String stripLimitedSeriesSuffix(String title) {
        if (title == null) {
            return "";
        }
        return LIMITED_SERIES_TRAILING.matcher(title).replaceAll("").trim();
    }

    /** Resolves and caches a show's complete episode guide, fetching it at most once per show. */
    private List<JsonNode> episodesForShow(long showId) {
        return episodesByShowId.computeIfAbsent(showId, id -> {
            List<JsonNode> all = new ArrayList<>();
            request("/shows/" + id + "/episodes").forEach(all::add);
            return all;
        });
    }

    /** Executes a TVMaze request, retrying transient failures up to 3 attempts. */
    private JsonNode request(String endpoint) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiRoot + endpoint))
                .header("Accept", "application/json")
                .timeout(requestTimeout)
                .GET()
                .build();
        final int maxAttempts = 3;
        IOException lastIo = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
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
                lastIo = exception;
                // Retry on timeouts and other transient I/O failures. Permanent HTTP errors are
                // thrown above and are not retried. After maxAttempts the exception is rethrown so
                // callers (including FallbackMetadataProvider) can degrade gracefully.
                if (attempt == maxAttempts) {
                    throw new IllegalStateException(
                            "Unable to call TVMaze after " + maxAttempts + " attempts", exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TVMaze request was interrupted", exception);
            }
        }
        // Unreachable: loop always returns or throws, but keeps the compiler happy.
        throw new IllegalStateException("Unable to call TVMaze after " + maxAttempts + " attempts", lastIo);
    }

    /** Maps TVMaze's nested search result to the provider-neutral metadata model. */
    static ShowMetadata toMetadata(JsonNode show, Integer totalEpisodes) {
        List<String> genres = new ArrayList<>();
        show.path("genres").forEach(genre -> genres.add(genre.asText()));
        Boolean productionEnded = show.hasNonNull("status") ? isEndedStatus(show.path("status").asText()) : null;
        return new ShowMetadata(
                "TVMAZE",
                show.path("id").asLong(),
                show.path("name").asText(),
                ShowType.SERIES,
                genres,
                stripHtml(nullable(show, "summary")),
                nullable(show.path("image"), "original"),
                date(nullable(show, "premiered")),
                totalEpisodes,
                productionEnded);
    }

    /**
     * TVMaze's "status" field distinguishes a finished run ("Ended") from one still releasing new
     * episodes ("Running") or not yet confirmed ("To Be Determined", "In Development"). Only
     * "Ended" is treated as finished; the others are conservatively treated as not-yet-finished
     * rather than guessed at.
     */
    private static boolean isEndedStatus(String status) {
        return "Ended".equalsIgnoreCase(status);
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
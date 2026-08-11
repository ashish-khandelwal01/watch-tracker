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

/** TMDb v3 provider using the official API Read Access Token bearer authentication. */
public final class TmdbMetadataProvider implements MetadataProvider {
    private static final String API_ROOT = "https://api.themoviedb.org/3";
    // Without these, a stalled connection or an unresponsive server (including a soft rate-limit
    // that never sends a response instead of returning 429) blocks java.net.http.HttpClient
    // indefinitely — there is no default timeout. A single hung request can then stall the entire
    // synchronous, single-threaded import for hours with no way to recover.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    // Any single call taking longer than this is logged to stderr, since it's either an outright
    // timeout (a strong signal of provider-side throttling) or a slow-but-successful response —
    // both are worth surfacing when diagnosing an unexpectedly long run.
    private static final long SLOW_CALL_THRESHOLD_MILLIS = 5_000;
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
        this(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                apiReadAccessToken,
                new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
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
        JsonNode details = request('/' + mediaType + '/' + id);
        ShowMetadata metadata = toMetadata(details, mediaType);
        if ("tv".equals(mediaType) && id > 0) {
            rememberSeriesMapping(title, id);
            if (metadata.title() != null && !metadata.title().isBlank() && !metadata.title().equals(title)) {
                rememberSeriesMapping(metadata.title(), id);
            }
            // The details response already carries number_of_seasons, so seed the same cache
            // seasonCount() uses to avoid an identical second /tv/{id} request the first time
            // resolveEpisodeAnySeason needs a season count for this series.
            if (details.hasNonNull("number_of_seasons")) {
                seasonCountBySeriesId.putIfAbsent(id, details.path("number_of_seasons").asInt());
            }
        }
        return Optional.of(metadata);
    }

    /**
     * Seeds the title → TMDb series-id map used by title-based episode resolution.
     *
     * @param title title key to associate
     * @param providerId TMDb series id
     */
    @Override
    public void rememberSeriesMapping(String title, long providerId) {
        if (title == null || title.isBlank() || providerId < 1) {
            return;
        }
        seriesIdByTitle.put(title.trim(), providerId);
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
     * stopping at the first TV/movie hit. (An earlier version of this method tried to break ties
     * between multiple exact matches by preferring whichever released most recently — e.g. to prefer
     * a streaming-era reboot over a same-titled decades-old original. In practice this did far more
     * harm than good: TMDb has no shortage of small, recently-added, or foreign productions that
     * happen to share an exact title with a famous movie or show, and "most recent" walked straight
     * into them — turning correct matches for "Twilight", "Heroes", "Back to the Future", "Catch Me
     * If You Can" and more into obscure, barely-watched lookalikes. That approach was reverted;
     * ambiguous same-titled catalog entries are left as a first-match pick like any other, and are a
     * better fit for manual correction than another blanket heuristic.)
     *
     * <p>When no exact match exists, a plain "first TV/movie hit" fallback can still pick a badly
     * wrong result: TMDb's text relevance can rank a tangentially-titled result (e.g. a making-of
     * documentary whose title happens to contain the query as a substring, such as "House M.D.: The
     * Doctor Directs" for a query of "House, M.D.") above the actual show ("House"), even though the
     * actual show's title is also textually related to the query and is the much closer match. So
     * among candidates whose normalized title is a substring of the query's (or vice versa), this
     * prefers whichever is closest in length to the query — i.e. the smallest amount of extra or
     * missing text — before falling back to the plain first-hit behavior. This only changes the
     * outcome when at least one candidate has this substring relationship; titles where TMDb's own
     * title legitimately differs with no textual overlap at all (alternate language title,
     * re-release naming, etc.) are unaffected and keep the previous first-hit fallback.
     *
     * @param results the {@code results} array from a {@code /search/multi} response
     * @param queryTitle the title that was searched for
     * @return the selected result node, or {@code null} if no TV/movie candidate exists
     */
    static JsonNode selectBestMatch(JsonNode results, String queryTitle) {
        JsonNode fallback = null;
        JsonNode closestRelated = null;
        int closestRelatedDistance = Integer.MAX_VALUE;
        String normalizedQuery = normalizeTitle(queryTitle);
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
            String normalizedResult = normalizeTitle(resultTitle);
            if (!normalizedQuery.isEmpty()
                    && !normalizedResult.isEmpty()
                    && (normalizedResult.contains(normalizedQuery) || normalizedQuery.contains(normalizedResult))) {
                int distance = Math.abs(normalizedResult.length() - normalizedQuery.length());
                if (distance < closestRelatedDistance) {
                    closestRelatedDistance = distance;
                    closestRelated = result;
                }
            }
        }
        return closestRelated != null ? closestRelated : fallback;
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
        return resolveEpisode(seriesId, season, episodeTitle);
    }

    /**
     * Resolves a named episode against a TMDb series already identified by id.
     *
     * @param providerId TMDb series id
     * @param season Netflix part or season number
     * @param episodeTitle Netflix episode title
     * @return canonical TMDb episode position when its title matches
     */
    @Override
    public Optional<WatchProgress> resolveEpisode(long providerId, int season, String episodeTitle) {
        if (providerId < 1 || episodeTitle == null || episodeTitle.isBlank()) {
            return Optional.empty();
        }
        for (JsonNode episode : episodesForSeason(providerId, season)) {
            if (sameTitle(episode.path("name").asText(), episodeTitle)) {
                int episodeNumber = episode.path("episode_number").asInt();
                return episodeNumber > 0
                        ? Optional.of(new WatchProgress(season, episodeNumber, "TMDB"))
                        : Optional.empty();
            }
        }
        return Optional.empty();
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
        return resolveEpisodeAnySeason(seriesId, episodeTitle);
    }

    /**
     * Resolves a named episode anywhere in a TMDb series already identified by id.
     *
     * @param providerId TMDb series id
     * @param episodeTitle Netflix episode title
     * @return canonical TMDb episode position when its title is found in any season
     */
    @Override
    public Optional<WatchProgress> resolveEpisodeAnySeason(long providerId, String episodeTitle) {
        if (providerId < 1 || episodeTitle == null || episodeTitle.isBlank()) {
            return Optional.empty();
        }
        for (int season = 1; season <= seasonCount(providerId); season++) {
            for (JsonNode episode : episodesForSeason(providerId, season)) {
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

    private long seriesId(String showTitle) {
        return seriesIdByTitle.computeIfAbsent(showTitle, title -> {
            long id = searchSeriesId(title);
            if (id > 0) {
                return id;
            }
            String cleaned = stripLimitedSeriesSuffix(title);
            if (!cleaned.equals(title) && !cleaned.isBlank()) {
                return searchSeriesId(cleaned);
            }
            return -1L;
        });
    }

    /** Searches TMDb TV and prefers an exact title match over popularity rank. */
    private long searchSeriesId(String title) {
        JsonNode results = request("/search/tv?include_adult=false&language=en-US&query=" + encode(title))
                .path("results");
        if (results.isEmpty()) {
            return -1L;
        }
        long fallback = -1L;
        for (JsonNode result : results) {
            long id = result.path("id").asLong();
            if (id <= 0) {
                continue;
            }
            if (fallback < 1) {
                fallback = id;
            }
            if (sameTitle(result.path("name").asText(), title)) {
                return id;
            }
        }
        return fallback;
    }

    private static final java.util.regex.Pattern LIMITED_SERIES_TRAILING =
            java.util.regex.Pattern.compile("(?i)\\s*:?\\s*limited series\\b\\s*$");

    private static String stripLimitedSeriesSuffix(String title) {
        if (title == null) {
            return "";
        }
        return LIMITED_SERIES_TRAILING.matcher(title).replaceAll("").trim();
    }

    /** Resolves and caches a series' season count, fetching series details at most once per series. */
    private int seasonCount(long seriesId) {
        return seasonCountBySeriesId.computeIfAbsent(
                seriesId, id -> request("/tv/" + id).path("number_of_seasons").asInt());
    }

    /** Executes an authenticated TMDb request, retrying transient failures up to 3 attempts. */
    private JsonNode request(String endpoint) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_ROOT + endpoint))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        final int maxAttempts = 3;
        IOException lastIo = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long attemptStart = System.nanoTime();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                logSlowCall("TMDb", endpoint, attempt, attemptStart, response.statusCode());
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
                logSlowCall("TMDb", endpoint, attempt, attemptStart, -1);
                lastIo = exception;
                // Retry on timeouts and other transient I/O failures (including soft rate-limits
                // that never send a response). Permanent HTTP errors are thrown above and are not
                // retried. After maxAttempts the exception is rethrown so FallbackMetadataProvider
                // can treat it as a primary miss and try TVMaze.
                if (attempt == maxAttempts) {
                    throw new IllegalStateException(
                            "Unable to call TMDb after " + maxAttempts + " attempts", exception);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("TMDb request was interrupted", exception);
            }
        }
        // Unreachable: loop always returns or throws, but keeps the compiler happy.
        throw new IllegalStateException("Unable to call TMDb after " + maxAttempts + " attempts", lastIo);
    }

    /**
     * Logs a single call's timing to stderr whenever it's slow enough to be a candidate explanation
     * for an unexpectedly long run — either a full-length timeout (a strong signal of provider-side
     * throttling that silently withholds a response rather than returning HTTP 429, since that's the
     * only way a single attempt reaches the configured request timeout) or just a slow-but-successful
     * response. A statusCode of -1 means the attempt failed with an I/O exception (timeout,
     * connection reset, etc.) rather than receiving a response at all.
     */
    private static void logSlowCall(String provider, String endpoint, int attempt, long attemptStartNanos, int statusCode) {
        long millis = (System.nanoTime() - attemptStartNanos) / 1_000_000;
        if (millis < SLOW_CALL_THRESHOLD_MILLIS) {
            return;
        }
        String outcome = statusCode < 0 ? "TIMED OUT / I/O ERROR" : "HTTP " + statusCode;
        System.err.printf(
                "  [%s] SLOW CALL: %s (attempt %d) took %d ms -> %s%n", provider, endpoint, attempt, millis, outcome);
    }

    /** Maps a TMDb details document to the provider-neutral metadata model. */
    static ShowMetadata toMetadata(JsonNode details, String mediaType) {
        List<String> genres = new ArrayList<>();
        details.path("genres").forEach(genre -> genres.add(genre.path("name").asText()));
        String title = "tv".equals(mediaType)
                ? details.path("name").asText()
                : details.path("title").asText();
        String date = "tv".equals(mediaType)
                ? details.path("first_air_date").asText()
                : details.path("release_date").asText();
        Integer totalEpisodes = "tv".equals(mediaType) && details.hasNonNull("number_of_episodes")
                ? details.path("number_of_episodes").asInt()
                : null;
        Boolean productionEnded = "tv".equals(mediaType) && details.hasNonNull("status")
                ? isEndedStatus(details.path("status").asText())
                : null;
        return new ShowMetadata(
                "TMDB",
                details.path("id").asLong(),
                title,
                "tv".equals(mediaType) ? ShowType.SERIES : ShowType.MOVIE,
                genres,
                nullable(details, "overview"),
                nullable(details, "poster_path"),
                date(date),
                totalEpisodes,
                productionEnded);
    }

    /**
     * TMDb's TV "status" field distinguishes a finished run ("Ended", "Canceled") from one still
     * releasing new episodes or seasons ("Returning Series", "In Production", "Planned", "Pilot").
     */
    private static boolean isEndedStatus(String status) {
        return "Ended".equalsIgnoreCase(status) || "Canceled".equalsIgnoreCase(status);
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
        // \p{Alnum} is a POSIX class that only matches US-ASCII letters/digits unless Java's
        // UNICODE_CHARACTER_CLASS flag is set, which this pattern doesn't set. That silently drops
        // any non-ASCII character instead of preserving it — including things like the superscript
        // "²" in "The Accountant²", which vanishes entirely and leaves the same normalized string as
        // "The Accountant", making two different movies register as an exact title match. \p{L}
        // (Unicode letter) and \p{N} (Unicode number, which includes superscript/subscript digits)
        // are true Unicode property escapes and match consistently regardless of that flag, so
        // meaningful characters are kept instead of erased.
        return title == null ? "" : title.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
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
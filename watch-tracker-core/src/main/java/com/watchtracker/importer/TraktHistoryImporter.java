package com.watchtracker.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.watchtracker.model.WatchEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports a user's Trakt watch history as raw {@link WatchEvent}s.
 *
 * <p><strong>Trakt is not a metadata catalogue for Netflix titles.</strong> Metadata still uses
 * TMDb / TVMaze. Trakt is an optional <em>history source</em> for going forward: Netflix CSV covers
 * complete past history; Trakt adds plays from other OTTs/scrobblers once the user connects.
 *
 * <p>Supports live {@code GET /users/me/history} (OAuth access token + client id) and offline parse
 * of a saved history JSON array.
 */
public final class TraktHistoryImporter {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraktHistoryImporter.class);
    private static final String API_BASE = "https://api.trakt.tv";
    private static final String PLATFORM = "TRAKT";
    private static final int PAGE_SIZE = 100;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** Parses a JSON array of Trakt history items from disk. */
    public List<WatchEvent> parseFile(Path historyJson) {
        if (historyJson == null || !Files.isRegularFile(historyJson)) {
            throw new TraktImportException("Trakt history file does not exist: " + historyJson);
        }
        try {
            return parseHistoryArray(mapper.readTree(historyJson.toFile()));
        } catch (IOException exception) {
            throw new TraktImportException("Unable to read Trakt history file: " + historyJson, exception);
        }
    }

    /**
     * Fetches the authenticated user's full watch history (paginated).
     *
     * @param clientId Trakt API app client id
     * @param accessToken OAuth access token for the user
     */
    public List<WatchEvent> fetchHistory(String clientId, String accessToken) {
        requireText(clientId, "TRAKT_CLIENT_ID");
        requireText(accessToken, "TRAKT_ACCESS_TOKEN");
        List<WatchEvent> all = new ArrayList<>();
        int page = 1;
        while (true) {
            JsonNode pageNodes = request(
                    "/users/me/history?page=" + page + "&limit=" + PAGE_SIZE,
                    clientId.trim(),
                    accessToken.trim());
            if (pageNodes == null || !pageNodes.isArray() || pageNodes.isEmpty()) {
                break;
            }
            all.addAll(parseHistoryArray(pageNodes));
            if (pageNodes.size() < PAGE_SIZE) {
                break;
            }
            page++;
            if (page > 500) {
                LOGGER.warn("Stopping Trakt history fetch after 500 pages as a safety bound");
                break;
            }
        }
        return List.copyOf(all);
    }

    List<WatchEvent> parseHistoryArray(JsonNode root) {
        if (root == null || !root.isArray()) {
            throw new TraktImportException("Trakt history payload must be a JSON array");
        }
        List<WatchEvent> events = new ArrayList<>();
        for (JsonNode item : root) {
            try {
                mapItem(item).ifPresent(events::add);
            } catch (RuntimeException exception) {
                LOGGER.warn("Skipping malformed Trakt history item: {}", exception.getMessage());
            }
        }
        return List.copyOf(events);
    }

    private Optional<WatchEvent> mapItem(JsonNode item) {
        if (item == null || item.isMissingNode()) {
            return Optional.empty();
        }
        LocalDateTime watchedAt = parseWatchedAt(item.path("watched_at").asText(null));
        if (watchedAt == null) {
            return Optional.empty();
        }
        String type = item.path("type").asText("").toLowerCase();
        String rawTitle;
        if ("movie".equals(type)) {
            rawTitle = item.path("movie").path("title").asText(null);
        } else if ("episode".equals(type)) {
            rawTitle = formatEpisodeTitle(item.path("show"), item.path("episode"));
        } else if ("show".equals(type)) {
            rawTitle = item.path("show").path("title").asText(null);
        } else {
            return Optional.empty();
        }
        if (rawTitle == null || rawTitle.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(WatchEvent.raw(rawTitle.trim(), watchedAt, PLATFORM));
    }

    /**
     * Builds a Netflix-like title so {@code TitleNormalizer} can recover season/episode:
     * {@code Show: Season N: Episode Title} or {@code Show: Season N: Episode M}.
     */
    static String formatEpisodeTitle(JsonNode show, JsonNode episode) {
        String showTitle = show.path("title").asText(null);
        if (showTitle == null || showTitle.isBlank()) {
            return null;
        }
        int season = episode.path("season").asInt(0);
        int number = episode.path("number").asInt(0);
        String episodeTitle = episode.path("title").asText(null);
        StringBuilder sb = new StringBuilder(showTitle.trim());
        if (season > 0) {
            sb.append(": Season ").append(season);
        }
        if (episodeTitle != null && !episodeTitle.isBlank()) {
            sb.append(": ").append(episodeTitle.trim());
        } else if (number > 0) {
            sb.append(": Episode ").append(number);
        }
        return sb.toString();
    }

    private static LocalDateTime parseWatchedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private JsonNode request(String pathAndQuery, String clientId, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE + pathAndQuery))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("trakt-api-version", "2")
                    .header("trakt-api-key", clientId)
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new TraktImportException(
                        "Trakt authentication failed (HTTP " + response.statusCode()
                                + "). Check TRAKT_CLIENT_ID and TRAKT_ACCESS_TOKEN.");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new TraktImportException(
                        "Trakt history request failed (HTTP " + response.statusCode() + ")");
            }
            return mapper.readTree(response.body());
        } catch (IOException exception) {
            throw new TraktImportException("Trakt history request failed: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TraktImportException("Trakt history request interrupted", exception);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new TraktImportException(name + " is required for live Trakt history import");
        }
    }
}

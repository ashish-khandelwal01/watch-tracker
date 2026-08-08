package com.watchtracker.metadata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** Stores successful metadata lookups as individual JSON files in a local directory. */
public final class FileMetadataCache {
    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Creates a cache rooted at the supplied directory.
     *
     * @param directory directory that will hold metadata JSON files
     */
    public FileMetadataCache(Path directory) {
        this.directory = directory;
    }

    /**
     * Returns cached metadata for a title when present.
     *
     * @param title canonical title used as the cache key
     * @return cached metadata, if present
     */
    public Optional<ShowMetadata> get(String title) {
        Path entry = entry(title);
        if (!Files.isRegularFile(entry)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(entry.toFile(), ShowMetadata.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read metadata cache entry " + entry, exception);
        }
    }

    /**
     * Persists metadata for a title.
     *
     * @param title canonical title used as the cache key
     * @param metadata metadata to persist
     */
    public void put(String title, ShowMetadata metadata) {
        try {
            Files.createDirectories(directory);
            mapper.writerWithDefaultPrettyPrinter().writeValue(entry(title).toFile(), metadata);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write metadata cache", exception);
        }
    }

    /**
     * Returns a cached episode position for a show, season, and episode title, when present.
     *
     * @param showTitle canonical show title
     * @param season Netflix container number
     * @param episodeTitle Netflix episode title
     * @return cached episode position, if present
     */
    public Optional<WatchProgress> getEpisode(String showTitle, int season, String episodeTitle) {
        return readProgress(episodeKey(showTitle, season, episodeTitle));
    }

    /**
     * Persists a resolved episode position for a show, season, and episode title.
     *
     * @param showTitle canonical show title
     * @param season Netflix container number
     * @param episodeTitle Netflix episode title
     * @param progress resolved episode position to persist
     */
    public void putEpisode(String showTitle, int season, String episodeTitle, WatchProgress progress) {
        writeProgress(episodeKey(showTitle, season, episodeTitle), progress);
    }

    /**
     * Returns a cached any-season episode position for a show and episode title.
     *
     * @param showTitle canonical show title
     * @param episodeTitle Netflix episode title
     * @return cached episode position, if present
     */
    public Optional<WatchProgress> getEpisodeAnySeason(String showTitle, String episodeTitle) {
        return readProgress(episodeAnySeasonKey(showTitle, episodeTitle));
    }

    /**
     * Persists a resolved any-season episode position.
     *
     * @param showTitle canonical show title
     * @param episodeTitle Netflix episode title
     * @param progress resolved episode position to persist
     */
    public void putEpisodeAnySeason(String showTitle, String episodeTitle, WatchProgress progress) {
        writeProgress(episodeAnySeasonKey(showTitle, episodeTitle), progress);
    }

    /**
     * Returns a cached episode position keyed by provider series id and season.
     *
     * @param providerId provider-specific series identifier
     * @param season Netflix container number
     * @param episodeTitle Netflix episode title
     * @return cached episode position, if present
     */
    public Optional<WatchProgress> getEpisodeById(long providerId, int season, String episodeTitle) {
        return readProgress(episodeIdKey(providerId, season, episodeTitle));
    }

    /**
     * Persists a resolved episode position keyed by provider series id and season.
     *
     * @param providerId provider-specific series identifier
     * @param season Netflix container number
     * @param episodeTitle Netflix episode title
     * @param progress resolved episode position to persist
     */
    public void putEpisodeById(long providerId, int season, String episodeTitle, WatchProgress progress) {
        writeProgress(episodeIdKey(providerId, season, episodeTitle), progress);
    }

    /**
     * Returns a cached any-season episode position keyed by provider series id.
     *
     * @param providerId provider-specific series identifier
     * @param episodeTitle Netflix episode title
     * @return cached episode position, if present
     */
    public Optional<WatchProgress> getEpisodeAnySeasonById(long providerId, String episodeTitle) {
        return readProgress(episodeIdAnySeasonKey(providerId, episodeTitle));
    }

    /**
     * Persists a resolved any-season episode position keyed by provider series id.
     *
     * @param providerId provider-specific series identifier
     * @param episodeTitle Netflix episode title
     * @param progress resolved episode position to persist
     */
    public void putEpisodeAnySeasonById(long providerId, String episodeTitle, WatchProgress progress) {
        writeProgress(episodeIdAnySeasonKey(providerId, episodeTitle), progress);
    }

    private Optional<WatchProgress> readProgress(String key) {
        Path entry = entry(key);
        if (!Files.isRegularFile(entry)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(entry.toFile(), WatchProgress.class));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read metadata cache entry " + entry, exception);
        }
    }

    private void writeProgress(String key, WatchProgress progress) {
        try {
            Files.createDirectories(directory);
            mapper.writerWithDefaultPrettyPrinter().writeValue(entry(key).toFile(), progress);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to write metadata cache", exception);
        }
    }

    /** Builds a stable composite key distinguishing episode entries from title entries. */
    private static String episodeKey(String showTitle, int season, String episodeTitle) {
        return "episode::" + showTitle + "::" + season + "::" + episodeTitle;
    }

    private static String episodeAnySeasonKey(String showTitle, String episodeTitle) {
        return "episode::any::" + showTitle + "::" + episodeTitle;
    }

    private static String episodeIdKey(long providerId, int season, String episodeTitle) {
        return "episode::id::" + providerId + "::" + season + "::" + episodeTitle;
    }

    private static String episodeIdAnySeasonKey(long providerId, String episodeTitle) {
        return "episode::id::any::" + providerId + "::" + episodeTitle;
    }

    /** Resolves the safe cache-file path for a title or composite key. */
    private Path entry(String title) {
        return directory.resolve(hash(title) + ".json");
    }

    /** Produces a stable, filesystem-safe SHA-256 key. */
    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

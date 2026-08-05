package com.watchtracker.metadata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

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

    /** Resolves the safe cache-file path for a title. */
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

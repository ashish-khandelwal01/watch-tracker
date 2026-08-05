package com.watchtracker.metadata;

import com.watchtracker.model.ShowType;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Provider-sourced metadata that is kept distinct from imported watch history.
 *
 * @param provider source provider name
 * @param providerId provider-specific stable identifier
 * @param title provider's canonical title
 * @param type provider-sourced show type
 * @param genres provider-sourced genre names
 * @param overview provider-sourced synopsis
 * @param posterPath provider-sourced poster path
 * @param releaseDate first release or air date
 */
public record ShowMetadata(
        String provider,
        long providerId,
        String title,
        ShowType type,
        List<String> genres,
        String overview,
        String posterPath,
        LocalDate releaseDate) {
    /** Creates immutable metadata from a trusted external provider. */
    public ShowMetadata {
        provider = requireText(provider, "provider");
        if (providerId <= 0) {
            throw new IllegalArgumentException("providerId must be positive");
        }
        title = requireText(title, "title");
        Objects.requireNonNull(type, "type must not be null");
        genres = List.copyOf(genres == null ? List.of() : genres);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}

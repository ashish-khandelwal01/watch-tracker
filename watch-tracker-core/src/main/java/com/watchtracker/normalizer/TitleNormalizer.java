package com.watchtracker.normalizer;

import com.watchtracker.model.WatchEvent;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts generic series metadata while preserving unknown title formats losslessly. */
public final class TitleNormalizer {
    private static final Pattern NUMBERED = Pattern.compile(
            "(?i)^(?<show>.+?):\\s*(?:(?:season|series|part|book|chapter|volume)\\s+(?<season>[0-9]+|[ivxlcdm]+)\\s*:\\s*)?(?:episodes?)\\s+(?<episode>[0-9]+|[ivxlcdm]+)(?:\\s*:\\s*(?<title>.+))?$");
    private static final Pattern SEASON_EPISODE = Pattern.compile(
            "(?i)^(?<show>.+?):\\s*(?:season|series)\\s+(?<season>[0-9]+|[ivxlcdm]+)\\s*:\\s*(?:episodes?)\\s+(?<episode>[0-9]+|[ivxlcdm]+)(?:\\s*:\\s*(?<title>.+))?$");
    private static final Pattern CONTAINER_TITLE = Pattern.compile(
            "(?i)^(?<show>.+?):\\s*(?:season|series|part|book|chapter|volume)\\s+(?<season>[0-9]+|[ivxlcdm]+)\\s*:\\s*(?<title>.+)$");

    /**
     * Normalizes a source event without changing its identifier or watch timestamp.
     *
     * @param event raw watch event to normalize
     * @return normalized copy of the event
     */
    public WatchEvent normalize(WatchEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        NormalizedTitle title = normalize(event.rawTitle());
        return event.normalized(title.canonicalShow(), title.season(), title.episode(), title.episodeTitle());
    }

    /**
     * Extracts title metadata using separators only where a generic episode marker proves intent.
     *
     * @param rawTitle platform-supplied title
     * @return extracted normalized title data
     */
    public NormalizedTitle normalize(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            throw new IllegalArgumentException("rawTitle must not be blank");
        }
        String trimmed = rawTitle.trim();
        Matcher matcher = NUMBERED.matcher(trimmed);
        if (!matcher.matches()) {
            matcher = SEASON_EPISODE.matcher(trimmed);
        }
        if (matcher.matches()) {
            return new NormalizedTitle(
                    matcher.group("show").trim(),
                    number(matcher.group("season")),
                    number(matcher.group("episode")),
                    nullableTrim(matcher.group("title")));
        }
        matcher = CONTAINER_TITLE.matcher(trimmed);
        if (matcher.matches()) {
            return new NormalizedTitle(
                    matcher.group("show").trim(),
                    number(matcher.group("season")),
                    null,
                    nullableTrim(matcher.group("title")));
        }
        int delimiter = trimmed.indexOf(": ");
        if (delimiter > 0) {
            return new NormalizedTitle(
                    trimmed.substring(0, delimiter).trim(),
                    null,
                    null,
                    trimmed.substring(delimiter + 2).trim());
        }
        return new NormalizedTitle(trimmed, null, null, null);
    }

    /** Trims an optional value and maps blanks to {@code null}. */
    private static String nullableTrim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Converts an Arabic or Roman numeral to an integer. */
    private static Integer number(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.toUpperCase(Locale.ROOT);
        if (candidate.matches("[0-9]+")) {
            return Integer.valueOf(candidate);
        }
        int total = 0;
        int previous = 0;
        for (int index = candidate.length() - 1; index >= 0; index--) {
            int current = romanValue(candidate.charAt(index));
            if (current < previous) {
                total -= current;
            } else {
                total += current;
                previous = current;
            }
        }
        return total;
    }

    /** Returns the numeric value associated with a Roman numeral character. */
    private static int romanValue(char character) {
        return switch (character) {
            case 'I' -> 1;
            case 'V' -> 5;
            case 'X' -> 10;
            case 'L' -> 50;
            case 'C' -> 100;
            case 'D' -> 500;
            case 'M' -> 1000;
            default -> throw new IllegalArgumentException("Invalid Roman numeral");
        };
    }
}

package com.watchtracker.normalizer;

import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.DiagnosticsStage;
import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.model.WatchEvent;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts generic series metadata while preserving unknown title formats losslessly. */
public final class TitleNormalizer {
    private final DiagnosticsCollector diagnostics;

    public TitleNormalizer() {
        this(new NoOpDiagnosticsCollector());
    }

    public TitleNormalizer(DiagnosticsCollector diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    // Spelled-out ordinal/cardinal words up to twenty, e.g. "Chapter One" or "Part Fourteen".
    // Longer compound words that share a prefix with a shorter one (fourteen/four, sixteen/six,
    // seventeen/seven, eighteen/eight, nineteen/nine) are listed first; regex alternation
    // backtracks to find a full match regardless of order, but listing them first avoids relying
    // on that backtracking.
    private static final Map<String, Integer> NUMBER_WORDS = buildNumberWords();
    private static final String NUMBER_WORD_PATTERN =
            "fourteen|sixteen|seventeen|eighteen|nineteen|one|two|three|four|five|six|seven|eight"
                    + "|nine|ten|eleven|twelve|thirteen|fifteen|twenty";
    // Accepts an Arabic numeral, a Roman numeral, or a spelled-out number word as a season or
    // episode marker.
    private static final String NUMBER_TOKEN = "(?:[0-9]+|[ivxlcdm]+|" + NUMBER_WORD_PATTERN + ")";

    // The inner clause accepts "episode" or "chapter" as an episode marker, so a bare "Chapter N"
    // with nothing further numbered after it resolves to an episode. When "chapter" (or another
    // season word) appears before this clause instead, it is still captured as the outer season,
    // e.g. "Part 2: Chapter 12" or "Chapter 2: Episode 8" both resolve chapter/part as the season.
    private static final Pattern NUMBERED = Pattern.compile("(?i)^(?<show>.+?):\\s*(?:(?:season|series|part|book"
            + "|chapter|volume)\\s+(?<season>" + NUMBER_TOKEN + ")\\s*:\\s*)?(?:episodes?|chapters?)\\s+(?<episode>"
            + NUMBER_TOKEN + ")(?:\\s*:\\s*(?<title>.+))?$");
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)^(?<show>.+?):\\s*(?:season|series)\\s+"
            + "(?<season>" + NUMBER_TOKEN + ")\\s*:\\s*(?:episodes?)\\s+(?<episode>" + NUMBER_TOKEN
            + ")(?:\\s*:\\s*(?<title>.+))?$");
    // "chapter" is deliberately absent here: any "Chapter N: Title" is already resolved by NUMBERED
    // above (as an episode when bare, or as a season when a nested episode/chapter marker follows),
    // so this clause would never actually be reached for it.
    private static final Pattern CONTAINER_TITLE = Pattern.compile("(?i)^(?<show>.+?):\\s*(?:season|series|part"
            + "|book|volume)\\s+(?<season>" + NUMBER_TOKEN + ")\\s*:\\s*(?<title>.+)$");

    /**
     * Normalizes a source event without changing its identifier or watch timestamp.
     *
     * @param event raw watch event to normalize
     * @return normalized copy of the event
     */
    public WatchEvent normalize(WatchEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        NormalizedTitle title = normalize(event.rawTitle());

        Map<String, Object> output = new LinkedHashMap<>();
        if (title.season() != null) {
            output.put("season", title.season());
        }
        if (title.episode() != null) {
            output.put("episode", title.episode());
        }

        diagnostics.success(DiagnosticsStage.TITLE_NORMALIZER, title.canonicalShow(), event.rawTitle(), output);

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
                    number(nullableTrim(matcher.group("season"))),
                    number(nullableTrim(matcher.group("episode"))),
                    nullableTrim(matcher.group("title")));
        }
        matcher = CONTAINER_TITLE.matcher(trimmed);
        if (matcher.matches()) {
            return new NormalizedTitle(
                    matcher.group("show").trim(),
                    number(nullableTrim(matcher.group("season"))),
                    null,
                    nullableTrim(matcher.group("title")));
        }
        int delimiter = trimmed.indexOf(": ");
        if (delimiter > 0) {
            // Anything past the first colon is a container/collection/arc label rather than the
            // actual episode title whenever a *further* colon follows it (e.g. "Show: Limited
            // Series: Episode Title", "Show: The Smuggler's Web: Episode Title"). Strip an
            // initial "Limited Series: " infix after the first colon to avoid polluting the
            // episode title (e.g. "Show: Limited Series: The Vixen" -> episode "The Vixen").
            String show = trimmed.substring(0, delimiter).trim();
            String afterFirst = trimmed.substring(delimiter + 2).trim();
            String episodeTitle;
            if (afterFirst.toLowerCase(Locale.ROOT).startsWith("limited series:")) {
                episodeTitle = afterFirst.replaceFirst("(?i)^limited series\\s*:\\s*", "").trim();
            } else {
                int lastDelimiter = trimmed.lastIndexOf(": ");
                episodeTitle = trimmed.substring(lastDelimiter + 2).trim();
            }
            return new NormalizedTitle(show, null, null, nullableTrim(episodeTitle));
        }
        return new NormalizedTitle(trimmed, null, null, null);
    }

    /** Trims an optional value and maps blanks to {@code null}. */
    private static String nullableTrim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Converts an Arabic numeral, spelled-out number word, or Roman numeral to an integer. */
    private static Integer number(String value) {
        if (value == null) {
            return null;
        }
        if (value.matches("[0-9]+")) {
            return Integer.valueOf(value);
        }
        Integer wordValue = NUMBER_WORDS.get(value.toLowerCase(Locale.ROOT));
        if (wordValue != null) {
            return wordValue;
        }
        return romanToInt(value.toUpperCase(Locale.ROOT));
    }

    /** Converts a Roman numeral string to an integer. */
    private static int romanToInt(String candidate) {
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

    /** Builds the spelled-out number word lookup, one through twenty. */
    private static Map<String, Integer> buildNumberWords() {
        String[] words = {
                "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
                "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"
        };
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int index = 0; index < words.length; index++) {
            map.put(words[index], index + 1);
        }
        return Map.copyOf(map);
    }
}
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

    // The inner clause accepts "episode", "chapter", "ep" or "ep." as an episode marker, so a bare "Chapter N"
    // or "Ep. N" with nothing further numbered after it resolves to an episode. When "chapter" (or another
    // season word) appears before this clause instead, it is still captured as the outer season,
    // e.g. "Part 2: Chapter 12" or "Chapter 2: Episode 8" both resolve chapter/part as the season.
    // Episode titles can be separated by either ":" or "-" after the episode number.
    private static final Pattern NUMBERED = Pattern.compile("(?i)^(?<show>.+?):\\s*(?:(?:season|series|part|book"
            + "|chapter|volume)\\s+(?<season>" + NUMBER_TOKEN + ")\\s*:\\s*)?(?:episodes?|chapters?|ep\\.?)\\s+(?<episode>"
            + NUMBER_TOKEN + ")(?:\\s*[-:]\\s*(?<title>.+))?$");
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)^(?<show>.+?):\\s*(?:season|series)\\s+"
            + "(?<season>" + NUMBER_TOKEN + ")\\s*:\\s*(?:episodes?|ep\\.?)\\s+(?<episode>" + NUMBER_TOKEN
            + ")(?:\\s*[-:]\\s*(?<title>.+))?$");
    // "chapter" is deliberately absent here: any "Chapter N: Title" is already resolved by NUMBERED
    // above (as an episode when bare, or as a season when a nested episode/chapter marker follows),
    // so this clause would never actually be reached for it.
    private static final Pattern CONTAINER_TITLE = Pattern.compile("(?i)^(?<show>.+?):\\s*(?:season|series|part"
            + "|book|volume)\\s+(?<season>" + NUMBER_TOKEN + ")\\s*:\\s*(?<title>.+)$");
    // Netflix sometimes labels a later season's container by repeating the show's own name with the
    // season number appended, e.g. "Stranger Things: Stranger Things 4: Chapter Eight: Papa". That
    // repeated segment isn't part of the real title — it's a season marker in disguise. Left alone,
    // the lazy "show" group in NUMBERED has no choice but to swallow it (there's no earlier point at
    // which the rest of the pattern can match), which fragments one show into a separate entry per
    // season. This is applied as a post-processing step to whatever "show" a branch above produced,
    // since the repetition can in principle show up regardless of which pattern matched.
    private static final Pattern SELF_REPEATING_SEASON =
            Pattern.compile("(?i)^(?<base>.+):\\s+\\k<base>\\s+(?<season>" + NUMBER_TOKEN + ")$");

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
            return collapseSelfRepeatingSeason(new NormalizedTitle(
                    matcher.group("show").trim(),
                    number(matcher.group("season")),
                    number(matcher.group("episode")),
                    nullableTrim(matcher.group("title"))));
        }
        matcher = CONTAINER_TITLE.matcher(trimmed);
        if (matcher.matches()) {
            return collapseSelfRepeatingSeason(new NormalizedTitle(
                    matcher.group("show").trim(),
                    number(matcher.group("season")),
                    null,
                    nullableTrim(matcher.group("title"))));
        }
        int delimiter = trimmed.lastIndexOf(": ");
        if (delimiter > 0) {
            // The LAST colon (not the first) marks the show/episode-title boundary here, so any
            // middle segments stay attached to the show name rather than being dropped or glued onto
            // the episode title. This matters because those segments are frequently part of the
            // show's actual registered title rather than a throwaway label — e.g. the real title is
            // "The Trauma Code: Heroes on Call", not "The Trauma Code" with "Heroes on Call" bolted
            // onto the episode title. A single colon is unaffected: first and last are the same
            // position, so ordinary "Show: Episode Title" titles are unchanged.
            //
            // "Limited Series" is the one common label that is NOT actually part of a show's real
            // title despite sitting in this same position (e.g. "My Royal Nemesis: Limited Series:
            // The Vixen and the Beast"). It is deliberately NOT special-cased here: this normalizer
            // has no way to distinguish a genuine subtitle from an injected label using string shape
            // alone. Instead, the show name is left as "My Royal Nemesis: Limited Series", and
            // MetadataEnrichmentService's existing findByTitle retry strips a trailing "Limited
            // Series" and searches again when the first lookup misses.
            String show = trimmed.substring(0, delimiter).trim();
            String episodeTitle = trimmed.substring(delimiter + 2).trim();
            return collapseSelfRepeatingSeason(new NormalizedTitle(show, null, null, nullableTrim(episodeTitle)));
        }
        return new NormalizedTitle(trimmed, null, null, null);
    }

    /**
     * Detects a show name of the form "X: X N" (the show's own name repeated, followed by a
     * number) and collapses it to show "X" with season N, instead of leaving the repeated segment
     * fused into the show name. Only applies when a season wasn't already captured some other way.
     */
    private static NormalizedTitle collapseSelfRepeatingSeason(NormalizedTitle title) {
        if (title.season() != null) {
            return title;
        }
        Matcher matcher = SELF_REPEATING_SEASON.matcher(title.canonicalShow());
        if (!matcher.matches()) {
            return title;
        }
        return new NormalizedTitle(
                matcher.group("base").trim(), number(matcher.group("season")), title.episode(), title.episodeTitle());
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
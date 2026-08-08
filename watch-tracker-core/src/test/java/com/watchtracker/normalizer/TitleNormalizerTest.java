package com.watchtracker.normalizer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies generic extraction across the common Netflix title forms. */
class TitleNormalizerTest {
    private final TitleNormalizer normalizer = new TitleNormalizer();

    @ParameterizedTest
    @MethodSource("numberedTitles")
    void extractsNumberedMetadata(String raw, String show, Integer season, Integer episode) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, season, episode, null));
    }

    @ParameterizedTest
    @MethodSource("numberedTitlesWithEpisodeTitle")
    void extractsNumberedMetadataAlongsideAnEpisodeTitle(
            String raw, String show, Integer season, Integer episode, String episodeTitle) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, season, episode, episodeTitle));
    }

    @ParameterizedTest
    @MethodSource("episodeTitles")
    void preservesUnnumberedEpisodeTitles(String raw, String show, Integer season, String episodeTitle) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, season, null, episodeTitle));
    }

    @ParameterizedTest
    @MethodSource("spelledOutNumberTitles")
    void extractsSpelledOutNumberWordsAsSeasonOrEpisodeNumbers(
            String raw, String show, Integer season, Integer episode, String episodeTitle) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, season, episode, episodeTitle));
    }

    @ParameterizedTest
    @MethodSource("multiColonTitles")
    void keepsMiddleColonSegmentsAsPartOfTheShowName(String raw, String show, String episodeTitle) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, null, null, episodeTitle));
    }

    @Test
    void singleColonTitleIsUnaffectedByMultiColonHandling() {
        // With only one colon, the first and last colon are the same position, so behavior is
        // identical to a plain "Show: Episode Title" split.
        NormalizedTitle result = normalizer.normalize("A Show: A Limited Series About Cats");

        assertThat(result).isEqualTo(new NormalizedTitle("A Show", null, null, "A Limited Series About Cats"));
    }

    @ParameterizedTest
    @MethodSource("selfRepeatingSeasonTitles")
    void collapsesSelfRepeatingSeasonLabelInsteadOfFragmentingTheShow(
            String raw, String show, Integer season, Integer episode, String episodeTitle) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, season, episode, episodeTitle));
    }

    @ParameterizedTest
    @MethodSource("abbreviatedEpisodeTitles")
    void extractsAbbreviatedEpisodeMarkersWithHyphenOrColonSeparators(
            String raw, String show, Integer season, Integer episode, String episodeTitle) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, season, episode, episodeTitle));
    }

    private static Stream<Arguments> selfRepeatingSeasonTitles() {
        return Stream.of(
                // Netflix labels later-season containers by repeating the show's own name with the
                // season number appended. Without collapsing this, "Stranger Things: Stranger Things
                // 4" becomes its own distinct show, separate from "Stranger Things" itself and from
                // every other season — exactly the kind of fragmentation that sends the wrong,
                // nonexistent title to the metadata provider.
                Arguments.of(
                        "Stranger Things: Stranger Things 4: Chapter Eight: Papa",
                        "Stranger Things",
                        4,
                        8,
                        "Papa"),
                Arguments.of(
                        "Stranger Things: Stranger Things 2: Chapter One: MADMAX",
                        "Stranger Things",
                        2,
                        1,
                        "MADMAX"),
                // A real, distinct subtitle must NOT be collapsed: "Eternal Monarch" isn't a repeat
                // of "The King", so this stays a genuine multi-word show name.
                Arguments.of("The King: Eternal Monarch: Episode 16", "The King: Eternal Monarch", null, 16, null));
    }

    private static Stream<Arguments> numberedTitles() {
        return Stream.of(
                Arguments.of("Money Heist: Part 3: Episode 5", "Money Heist", 3, 5),
                Arguments.of("Business Proposal: Episode 6", "Business Proposal", null, 6),
                Arguments.of("Saga: Book IV: Episode IX", "Saga", 4, 9),
                Arguments.of("Saga: Chapter 2: Episode 8", "Saga", 2, 8),
                Arguments.of("Dark: Chapter 3", "Dark", null, 3),
                Arguments.of("Money Heist: Part 2: Chapter 12", "Money Heist", 2, 12));
    }

    private static Stream<Arguments> numberedTitlesWithEpisodeTitle() {
        return Stream.of(
                // A bare chapter marker with no nested season word resolves to an episode, not a
                // season, even though "Chapter" alone can also introduce a season (see
                // "Saga: Chapter 2: Episode 8" above) when a further numbered marker follows it.
                Arguments.of("Dark: Chapter 3: Adam and Eva", "Dark", null, 3, "Adam and Eva"),
                Arguments.of(
                        "Money Heist: Part 2: Chapter 12: Between Dogs and Wolves",
                        "Money Heist",
                        2,
                        12,
                        "Between Dogs and Wolves"));
    }

    private static Stream<Arguments> episodeTitles() {
        return Stream.of(
                Arguments.of(
                        "Two and a Half Men: Big Flappy Bastards", "Two and a Half Men", null, "Big Flappy Bastards"),
                Arguments.of(
                        "The Haunting of Hill House: Screaming Meemies",
                        "The Haunting of Hill House",
                        null,
                        "Screaming Meemies"),
                Arguments.of("Money Heist: Part 3: Aikido", "Money Heist", 3, "Aikido"),
                Arguments.of("Money Heist: Part III: We're Back", "Money Heist", 3, "We're Back"),
                // Only one colon, so there's no middle segment to drop.
                Arguments.of("Lockwood & Co.: This Will Be Us", "Lockwood & Co.", null, "This Will Be Us"));
    }

    private static Stream<Arguments> spelledOutNumberTitles() {
        return Stream.of(
                Arguments.of("Dark: Chapter Three", "Dark", null, 3, null),
                Arguments.of("Jane The Virgin: Chapter One: Pilot", "Jane The Virgin", null, 1, "Pilot"),
                Arguments.of(
                        "Stranger Things: Stranger Things 2: Chapter Eight: The Mind Flayer",
                        "Stranger Things",
                        2,
                        8,
                        "The Mind Flayer"),
                Arguments.of("Dark: Season Two: Episode Five", "Dark", 2, 5, null),
                Arguments.of("Money Heist: Part Fourteen: Some Title", "Money Heist", 14, null, "Some Title"),
                // "Seventeen" shares the prefix "seven" with a shorter word in the alternation;
                // this guards against a partial match leaving a stray "teen" behind.
                Arguments.of("Show: Chapter Seventeen: Finale", "Show", null, 17, "Finale"));
    }

    private static Stream<Arguments> multiColonTitles() {
        return Stream.of(
                // These middle segments are genuinely part of the show's real registered title, not
                // a throwaway label — dropping them (as an earlier version of this normalizer did)
                // sends the wrong, truncated name to the metadata provider and risks matching an
                // unrelated show entirely.
                Arguments.of(
                        "The Trauma Code: Heroes on Call: Code Black",
                        "The Trauma Code: Heroes on Call",
                        "Code Black"),
                Arguments.of("Taskaree: The Smuggler's Web: Kahani Khatam", "Taskaree: The Smuggler's Web", "Kahani Khatam"),
                Arguments.of(
                        "World War II: From the Frontlines: Turning Point",
                        "World War II: From the Frontlines",
                        "Turning Point"),
                Arguments.of(
                        "Shadowhunters: The Mortal Instruments: Bad Blood",
                        "Shadowhunters: The Mortal Instruments",
                        "Bad Blood"),
                Arguments.of(
                        "Testament: The Story of Moses: Part Three The Promised Land",
                        "Testament: The Story of Moses",
                        "Part Three The Promised Land"),
                // "Limited Series" is NOT part of the real title, but it is deliberately left in the
                // show name here rather than stripped — see the comment above the generic-split
                // branch in TitleNormalizer for why. MetadataEnrichmentService is responsible for
                // stripping it on retry.
                Arguments.of(
                        "My Royal Nemesis: Limited Series: The Vixen and the Beast",
                        "My Royal Nemesis: Limited Series",
                        "The Vixen and the Beast"));
    }

    private static Stream<Arguments> abbreviatedEpisodeTitles() {
        return Stream.of(
                // Netflix often uses abbreviated episode markers with hyphen separators
                Arguments.of(
                        "Lock Upp: Ep. 1 - Famous, Accused, Locked.",
                        "Lock Upp",
                        null,
                        1,
                        "Famous, Accused, Locked."),
                Arguments.of(
                        "Lock Upp: Ep. 2 - Tagged and Targeted",
                        "Lock Upp",
                        null,
                        2,
                        "Tagged and Targeted"),
                // "Ep." with colon separator (alternative format)
                Arguments.of(
                        "Show Name: Ep. 3: Episode Title",
                        "Show Name",
                        null,
                        3,
                        "Episode Title"),
                // "Ep" without period and hyphen separator
                Arguments.of(
                        "Show Name: Ep 4 - Some Title",
                        "Show Name",
                        null,
                        4,
                        "Some Title"),
                // Season + abbreviated episode with hyphen
                Arguments.of(
                        "Show Name: Season 2: Ep. 5 - Title",
                        "Show Name",
                        2,
                        5,
                        "Title"),
                // Part + abbreviated episode with colon
                Arguments.of(
                        "Show Name: Part 1: Ep. 6: Title Here",
                        "Show Name",
                        1,
                        6,
                        "Title Here"));
    }
}
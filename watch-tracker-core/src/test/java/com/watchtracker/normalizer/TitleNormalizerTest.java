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
    @MethodSource("middleSegmentInfixTitles")
    void dropsMiddleColonSegmentsKeepingOnlyTheFinalEpisodeTitle(String raw, String show, String episodeTitle) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, null, null, episodeTitle));
    }

    @Test
    void singleColonTitleIsUnaffectedByMiddleSegmentHandling() {
        // With only one colon, the first and last colon are the same position, so there is no
        // middle segment to drop and the remainder passes through untouched.
        NormalizedTitle result = normalizer.normalize("A Show: A Limited Series About Cats");

        assertThat(result).isEqualTo(new NormalizedTitle("A Show", null, null, "A Limited Series About Cats"));
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
                        "Stranger Things: Stranger Things 2",
                        null,
                        8,
                        "The Mind Flayer"),
                Arguments.of("Dark: Season Two: Episode Five", "Dark", 2, 5, null),
                Arguments.of("Money Heist: Part Fourteen: Some Title", "Money Heist", 14, null, "Some Title"),
                // "Seventeen" shares the prefix "seven" with a shorter word in the alternation;
                // this guards against a partial match leaving a stray "teen" behind.
                Arguments.of("Show: Chapter Seventeen: Finale", "Show", null, 17, "Finale"));
    }

    private static Stream<Arguments> middleSegmentInfixTitles() {
        return Stream.of(
                // "Limited Series" is the most common middle segment, but it's just one instance of
                // a general shape: a container/collection/arc label sitting between the show and the
                // actual episode title.
                Arguments.of(
                        "My Royal Nemesis: Limited Series: The Vixen and the Beast",
                        "My Royal Nemesis",
                        "The Vixen and the Beast"),
                Arguments.of(
                        "Boyfriend on Demand: Limited Series: Cliche or Classic",
                        "Boyfriend on Demand",
                        "Cliche or Classic"),
                // Arbitrary, show-specific arc/collection subtitles hit the same problem and are
                // handled the same way, since there's no fixed literal string to match against.
                Arguments.of("Taskaree: The Smuggler's Web: Kahani Khatam", "Taskaree", "Kahani Khatam"),
                Arguments.of(
                        "World War II: From the Frontlines: Turning Point", "World War II", "Turning Point"),
                Arguments.of(
                        "Shadowhunters: The Mortal Instruments: Bad Blood", "Shadowhunters", "Bad Blood"),
                // Three colons: the middle two segments ("The Story of Moses") are dropped, keeping
                // only the true trailing episode title.
                Arguments.of(
                        "Testament: The Story of Moses: Part Three The Promised Land",
                        "Testament",
                        "Part Three The Promised Land"));
    }
}
package com.watchtracker.normalizer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
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
    @MethodSource("episodeTitles")
    void preservesUnnumberedEpisodeTitles(String raw, String show, Integer season, String episodeTitle) {
        NormalizedTitle result = normalizer.normalize(raw);

        assertThat(result).isEqualTo(new NormalizedTitle(show, season, null, episodeTitle));
    }

    private static Stream<Arguments> numberedTitles() {
        return Stream.of(
                Arguments.of("Money Heist: Part 3: Episode 5", "Money Heist", 3, 5),
                Arguments.of("Business Proposal: Episode 6", "Business Proposal", null, 6),
                Arguments.of("Saga: Book IV: Episode IX", "Saga", 4, 9),
                Arguments.of("Saga: Chapter 2: Episode 8", "Saga", 2, 8));
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
                Arguments.of("Money Heist: Part III: We're Back", "Money Heist", 3, "We're Back"));
    }
}

package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonEnrichedShowSnapshotTest {

    @Test
    void matchScorePrefersExactTitleThenAliasThenColonPrefix() {
        Show prior = show(
                "The Lady's Companion",
                Set.of(
                        "The Lady's Companion: Lesson 1: Never Fall in Love",
                        "The Lady's Companion: Lesson 2: Do Not Tell Lies"));
        Show exact = show("The Lady's Companion", Set.of("The Lady's Companion: Lesson 1: Never Fall in Love"));
        Show episodeLevel = show(
                "The Lady's Companion: Lesson 1",
                Set.of("The Lady's Companion: Lesson 1: Never Fall in Love"));
        Show unrelated = show("Money Heist", Set.of("Money Heist: Part 1: Episode 1"));

        assertThat(JsonEnrichedShowSnapshot.matchScore(exact, prior)).isGreaterThan(100_000);
        assertThat(JsonEnrichedShowSnapshot.matchScore(episodeLevel, prior)).isGreaterThan(0);
        assertThat(JsonEnrichedShowSnapshot.matchScore(episodeLevel, prior))
                .isLessThan(JsonEnrichedShowSnapshot.matchScore(exact, prior));
        assertThat(JsonEnrichedShowSnapshot.matchScore(unrelated, prior)).isZero();
    }

    private static Show show(String title, Set<String> aliases) {
        return new Show(
                UUID.nameUUIDFromBytes(title.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                title,
                aliases,
                ShowType.UNKNOWN,
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 1, 1, 0, 0),
                null,
                null,
                aliases.size());
    }
}

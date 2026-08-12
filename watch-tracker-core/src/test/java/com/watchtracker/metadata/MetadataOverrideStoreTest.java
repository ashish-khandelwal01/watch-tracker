package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link MetadataOverrideStore}'s matching precedence, media-type normalization, file
 * loading, and sticky-merge behavior in isolation from the larger enrichment flow.
 */
class MetadataOverrideStoreTest {

    // ---- of(...): construction, validation, and skip behavior ----

    @Test
    void indexesAValidOverrideByItsExactTitle() {
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Kingsman", "TMDB", "movie", 9884)));

        Optional<MetadataOverride> found = store.forTitle("Kingsman");

        assertThat(found).isPresent();
        assertThat(found.get().providerId()).isEqualTo(9884);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void skipsAnEntryWithABlankOrNullTitle() {
        MetadataOverrideStore store = MetadataOverrideStore.of(List.of(
                new MetadataOverride("", "TMDB", "movie", 1),
                new MetadataOverride("   ", "TMDB", "movie", 2),
                new MetadataOverride(null, "TMDB", "movie", 3)));

        assertThat(store.size()).isZero();
    }

    @Test
    void skipsAnEntryWithAnUnrecognizedMediaType() {
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Kingsman", "TMDB", "documentary", 9884)));

        assertThat(store.size()).isZero();
        assertThat(store.forTitle("Kingsman")).isEmpty();
    }

    @Test
    void skipsAnEntryWithABlankOrMissingMediaType() {
        MetadataOverrideStore store = MetadataOverrideStore.of(List.of(
                new MetadataOverride("Kingsman", "TMDB", "", 9884),
                new MetadataOverride("Little", "TMDB", null, 1)));

        assertThat(store.size()).isZero();
    }

    @Test
    void acceptsMovieAndTvCaseInsensitively() {
        MetadataOverrideStore store = MetadataOverrideStore.of(List.of(
                new MetadataOverride("A Movie", "TMDB", "MOVIE", 1),
                new MetadataOverride("A Show", "TMDB", "Tv", 2)));

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.forTitle("A Movie")).isPresent();
        assertThat(store.forTitle("A Show")).isPresent();
    }

    @Test
    void acceptsSeriesAsASynonymForTvAndNormalizesIt() {
        MetadataOverrideStore store = MetadataOverrideStore.of(
                List.of(new MetadataOverride("The Lady's Companion", "TMDB", "Series", 280890)));

        Optional<MetadataOverride> found = store.forTitle("The Lady's Companion");

        assertThat(found).isPresent();
        // Regression guard: this is the exact typo ("Series" instead of "tv") that broke the first
        // real-world attempt at this feature — the stored mediaType must come out normalized to
        // "tv", not the raw "Series" string, since that's what MetadataProvider#findById expects.
        assertThat(found.get().mediaType()).isEqualTo("tv");
    }

    @Test
    void trimsWhitespaceFromTitleAndKeepsOriginalProviderCasing() {
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("  Kingsman  ", "TMDB", "movie", 9884)));

        assertThat(store.forTitle("Kingsman")).isPresent();
        assertThat(store.forTitle("  Kingsman  ")).isEmpty();
    }

    @Test
    void laterEntryForTheSameTitleWinsOverAnEarlierOne() {
        MetadataOverrideStore store = MetadataOverrideStore.of(List.of(
                new MetadataOverride("Kingsman", "TMDB", "movie", 111),
                new MetadataOverride("Kingsman", "TMDB", "movie", 222)));

        assertThat(store.forTitle("Kingsman")).map(MetadataOverride::providerId).contains(222L);
        assertThat(store.size()).isEqualTo(1);
    }

    // ---- normalizeMediaType(...) ----

    @Test
    void normalizeMediaTypeMapsRecognizedValuesAndRejectsEverythingElse() {
        assertThat(MetadataOverrideStore.normalizeMediaType("movie")).contains("movie");
        assertThat(MetadataOverrideStore.normalizeMediaType("MOVIE")).contains("movie");
        assertThat(MetadataOverrideStore.normalizeMediaType("tv")).contains("tv");
        assertThat(MetadataOverrideStore.normalizeMediaType("TV")).contains("tv");
        assertThat(MetadataOverrideStore.normalizeMediaType("series")).contains("tv");
        assertThat(MetadataOverrideStore.normalizeMediaType("Series")).contains("tv");
        assertThat(MetadataOverrideStore.normalizeMediaType(" tv ")).contains("tv");
        assertThat(MetadataOverrideStore.normalizeMediaType(null)).isEmpty();
        assertThat(MetadataOverrideStore.normalizeMediaType("")).isEmpty();
        assertThat(MetadataOverrideStore.normalizeMediaType("   ")).isEmpty();
        assertThat(MetadataOverrideStore.normalizeMediaType("documentary")).isEmpty();
    }

    // ---- forShow(...): matching precedence ----

    @Test
    void matchesByExactCanonicalTitle() {
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Kingsman", "TMDB", "movie", 9884)));

        assertThat(store.forShow("Kingsman", Set.of())).isPresent();
    }

    @Test
    void matchesByExactAliasWhenTitleDoesNotMatch() {
        MetadataOverrideStore store = MetadataOverrideStore.of(
                List.of(new MetadataOverride("Gabriel Iglesias: Stadium Fluffy", "TMDB", "movie", 123)));

        Optional<MetadataOverride> found = store.forShow(
                "Gabriel Iglesias", Set.of("Gabriel Iglesias: Stadium Fluffy", "Gabriel Iglesias: Legend of Fluffy"));

        assertThat(found).isPresent();
        assertThat(found.get().providerId()).isEqualTo(123);
    }

    @Test
    void matchesByColonPrefixOnTheCanonicalTitle() {
        MetadataOverrideStore store = MetadataOverrideStore.of(
                List.of(new MetadataOverride("The Lady's Companion", "TMDB", "tv", 280890)));

        Optional<MetadataOverride> lesson1 = store.forShow("The Lady's Companion: Lesson 1", Set.of());
        Optional<MetadataOverride> lesson2 = store.forShow("The Lady's Companion: Lesson 2", Set.of());

        assertThat(lesson1).isPresent();
        assertThat(lesson1.get().providerId()).isEqualTo(280890);
        assertThat(lesson2).isPresent();
        assertThat(lesson2.get().providerId()).isEqualTo(280890);
    }

    @Test
    void colonPrefixMatchRequiresTheColonSpaceSeparatorNotJustAStringPrefix() {
        // "The Lady" is a string-prefix of "The Lady's Companion: Lesson 1" but not a colon-prefix
        // of it, and must not match — otherwise a short, unrelated override title could hijack
        // unrelated shows that merely start with the same characters.
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("The Lady", "TMDB", "tv", 1)));

        assertThat(store.forShow("The Lady's Companion: Lesson 1", Set.of())).isEmpty();
    }

    @Test
    void exactTitleMatchWinsOverAColonPrefixMatchOnAShorterKey() {
        MetadataOverrideStore store = MetadataOverrideStore.of(List.of(
                new MetadataOverride("The Lady's Companion", "TMDB", "tv", 1),
                new MetadataOverride("The Lady's Companion: Lesson 1", "TMDB", "tv", 2)));

        Optional<MetadataOverride> found = store.forShow("The Lady's Companion: Lesson 1", Set.of());

        assertThat(found).isPresent();
        assertThat(found.get().providerId()).isEqualTo(2);
    }

    @Test
    void exactAliasMatchWinsOverAShorterColonPrefixMatch() {
        MetadataOverrideStore store = MetadataOverrideStore.of(List.of(
                new MetadataOverride("Gabriel Iglesias", "TMDB", "movie", 1),
                new MetadataOverride("Gabriel Iglesias: Stadium Fluffy", "TMDB", "movie", 2)));

        Optional<MetadataOverride> found =
                store.forShow("Gabriel Iglesias", Set.of("Gabriel Iglesias: Stadium Fluffy"));

        // The alias key ("Gabriel Iglesias: Stadium Fluffy") is longer than the colon-prefix key
        // ("Gabriel Iglesias"), so the more specific, alias-targeted override wins.
        assertThat(found).isPresent();
        assertThat(found.get().providerId()).isEqualTo(2);
    }

    @Test
    void returnsEmptyWhenNothingMatches() {
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Kingsman", "TMDB", "movie", 9884)));

        assertThat(store.forShow("Completely Unrelated Show", Set.of("Some Alias"))).isEmpty();
    }

    @Test
    void forShowToleratesANullAliasesCollectionAndBlankOrNullEntriesWithinIt() {
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Kingsman", "TMDB", "movie", 9884)));

        assertThat(store.forShow("Kingsman", null)).isPresent();
        assertThat(store.forShow("Kingsman", java.util.Arrays.asList(null, "", "   "))).isPresent();
    }

    @Test
    void forShowRejectsANullTitle() {
        MetadataOverrideStore store = MetadataOverrideStore.EMPTY;

        org.junit.jupiter.api.Assertions.assertThrows(
                NullPointerException.class, () -> store.forShow(null, Set.of()));
    }

    @Test
    void forTitleDelegatesToForShowWithNoAliases() {
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Kingsman", "TMDB", "movie", 9884)));

        assertThat(store.forTitle("Kingsman")).isEqualTo(store.forShow("Kingsman", Set.of()));
    }

    // ---- EMPTY ----

    @Test
    void emptyStoreHasNoEntriesAndMatchesNothing() {
        assertThat(MetadataOverrideStore.EMPTY.size()).isZero();
        assertThat(MetadataOverrideStore.EMPTY.forTitle("Anything")).isEmpty();
    }

    // ---- load(Path) ----

    @Test
    void loadReturnsEmptyForANullPath() {
        assertThat(MetadataOverrideStore.load(null)).isSameAs(MetadataOverrideStore.EMPTY);
    }

    @Test
    void loadReturnsEmptyWhenTheFileDoesNotExist(@TempDir Path dir) {
        Path missing = dir.resolve("does-not-exist.json");

        assertThat(MetadataOverrideStore.load(missing)).isSameAs(MetadataOverrideStore.EMPTY);
    }

    @Test
    void loadReturnsEmptyWhenThePathIsADirectoryNotAFile(@TempDir Path dir) {
        assertThat(MetadataOverrideStore.load(dir)).isSameAs(MetadataOverrideStore.EMPTY);
    }

    @Test
    void loadParsesAValidOverridesFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("overrides.json");
        Files.writeString(
                file,
                """
                [
                  { "title": "Kingsman", "provider": "TMDB", "mediaType": "movie", "providerId": 9884 },
                  { "title": "The Lady's Companion", "provider": "TMDB", "mediaType": "series", "providerId": 280890 }
                ]
                """);

        MetadataOverrideStore store = MetadataOverrideStore.load(file);

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.forTitle("Kingsman")).map(MetadataOverride::providerId).contains(9884L);
        assertThat(store.forShow("The Lady's Companion: Lesson 1", Set.of()))
                .map(MetadataOverride::providerId)
                .contains(280890L);
    }

    @Test
    void loadReturnsEmptyForMalformedJsonRatherThanThrowing(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("overrides.json");
        Files.writeString(file, "{ this is not valid json");

        assertThat(MetadataOverrideStore.load(file)).isSameAs(MetadataOverrideStore.EMPTY);
    }

    @Test
    void loadDropsEntriesWithAnUnrecognizedMediaTypeButKeepsTheRest(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("overrides.json");
        Files.writeString(
                file,
                """
                [
                  { "title": "Kingsman", "provider": "TMDB", "mediaType": "documentary", "providerId": 9884 },
                  { "title": "Little", "provider": "TMDB", "mediaType": "movie", "providerId": 1 }
                ]
                """);

        MetadataOverrideStore store = MetadataOverrideStore.load(file);

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.forTitle("Kingsman")).isEmpty();
        assertThat(store.forTitle("Little")).isPresent();
    }

    // ---- mergeWith(...): sticky overrides ----

    @Test
    void mergeWithReturnsTheSameInstanceWhenStickyListIsNullOrEmpty() {
        MetadataOverrideStore store =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Kingsman", "TMDB", "movie", 9884)));

        assertThat(store.mergeWith(null)).isSameAs(store);
        assertThat(store.mergeWith(List.of())).isSameAs(store);
    }

    @Test
    void mergeWithAddsStickyOverridesNotPresentInTheFileStore() {
        MetadataOverrideStore fileStore =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Shooter", "TMDB", "movie", 7485)));
        List<MetadataOverride> sticky =
                List.of(new MetadataOverride("The Lady's Companion", "TMDB", "tv", 280890));

        MetadataOverrideStore merged = fileStore.mergeWith(sticky);

        assertThat(merged.size()).isEqualTo(2);
        assertThat(merged.forTitle("Shooter")).isPresent();
        assertThat(merged.forTitle("The Lady's Companion")).isPresent();
    }

    @Test
    void mergeWithLetsTheFileStoreWinOverAStickyEntryForTheSameTitle() {
        // The whole point of overrides.json is that an explicit, currently-configured correction
        // can supersede whatever a prior run's MANUAL_OVERRIDE row said — otherwise there would be
        // no way to actually change a sticky override once made, short of editing shows.json by
        // hand.
        MetadataOverrideStore fileStore =
                MetadataOverrideStore.of(List.of(new MetadataOverride("Kingsman", "TMDB", "movie", 999)));
        List<MetadataOverride> sticky = List.of(new MetadataOverride("Kingsman", "TMDB", "movie", 111));

        MetadataOverrideStore merged = fileStore.mergeWith(sticky);

        assertThat(merged.size()).isEqualTo(1);
        assertThat(merged.forTitle("Kingsman")).map(MetadataOverride::providerId).contains(999L);
    }

    @Test
    void mergeWithSkipsStickyEntriesThatAreBlankNullOrHaveAnUnrecognizedMediaType() {
        MetadataOverrideStore fileStore = MetadataOverrideStore.EMPTY;
        List<MetadataOverride> sticky = java.util.Arrays.asList(
                new MetadataOverride("", "TMDB", "movie", 1),
                new MetadataOverride(null, "TMDB", "movie", 2),
                (MetadataOverride) null,
                new MetadataOverride("Bad Type", "TMDB", "documentary", 3),
                new MetadataOverride("Good", "TMDB", "movie", 4));

        MetadataOverrideStore merged = fileStore.mergeWith(sticky);

        assertThat(merged.size()).isEqualTo(1);
        assertThat(merged.forTitle("Good")).isPresent();
    }

    @Test
    void mergeWithNormalizesAStickySeriesMediaTypeToTv() {
        MetadataOverrideStore fileStore = MetadataOverrideStore.EMPTY;
        List<MetadataOverride> sticky =
                List.of(new MetadataOverride("The Lady's Companion", "TMDB", "series", 280890));

        MetadataOverrideStore merged = fileStore.mergeWith(sticky);

        assertThat(merged.forTitle("The Lady's Companion")).map(MetadataOverride::mediaType).contains("tv");
    }
}
package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RevokedOverrideStoreTest {

    @TempDir
    Path temp;

    @Test
    void loadsStringAndObjectTitlesAndMatchesPrefix() throws Exception {
        Path file = temp.resolve("revoked-overrides.json");
        Files.writeString(
                file,
                """
                [
                  "The Lady's Companion",
                  { "title": "Kingsman" }
                ]
                """);
        RevokedOverrideStore store = RevokedOverrideStore.load(file);

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.isRevoked("The Lady's Companion")).isTrue();
        assertThat(store.isRevoked("The Lady's Companion: Lesson 1")).isTrue();
        assertThat(store.isRevoked("Kingsman", Set.of("Kingsman: The Secret Service"))).isTrue();
        assertThat(store.isRevoked("Dark")).isFalse();
    }

    @Test
    void filterStickyDropsMatchingKeys() throws Exception {
        Path file = temp.resolve("revoked-overrides.json");
        Files.writeString(file, """
                ["The Lady's Companion"]
                """);
        RevokedOverrideStore store = RevokedOverrideStore.load(file);
        List<MetadataOverride> sticky = List.of(
                new MetadataOverride("The Lady's Companion", "TMDB", "tv", 280890),
                new MetadataOverride("Dark", "TMDB", "tv", 70523));

        var filtered = store.filterSticky(sticky);

        assertThat(filtered.droppedCount()).isEqualTo(1);
        assertThat(filtered.kept()).hasSize(1);
        assertThat(filtered.kept().get(0).title()).isEqualTo("Dark");
    }
}

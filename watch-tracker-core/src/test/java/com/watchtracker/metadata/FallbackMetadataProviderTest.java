package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.model.ShowType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Verifies that the fallback provider is only consulted after a primary miss. */
class FallbackMetadataProviderTest {
    @Test
    void usesFallbackWhenPrimaryHasNoMatch() {
        ShowMetadata fallbackMetadata =
                new ShowMetadata("TVMAZE", 1, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null);
        MetadataProvider provider =
                new FallbackMetadataProvider(title -> Optional.empty(), title -> Optional.of(fallbackMetadata));

        assertThat(provider.findByTitle("Dark")).contains(fallbackMetadata);
    }

    @Test
    void doesNotConsultFallbackWhenPrimaryMatches() {
        ShowMetadata primaryMetadata =
                new ShowMetadata("TMDB", 2, "Dark", ShowType.SERIES, List.of("Drama"), null, null, null);
        MetadataProvider provider = new FallbackMetadataProvider(title -> Optional.of(primaryMetadata), title -> {
            throw new AssertionError("Fallback must not be called after a primary match");
        });

        assertThat(provider.findByTitle("Dark")).contains(primaryMetadata);
    }
}

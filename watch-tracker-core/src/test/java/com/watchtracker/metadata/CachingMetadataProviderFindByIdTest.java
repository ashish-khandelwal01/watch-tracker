package com.watchtracker.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import com.watchtracker.model.ShowType;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachingMetadataProviderFindByIdTest {

    @TempDir
    Path temp;

    @Test
    void findByIdUsesCacheOnSecondCall() {
        AtomicInteger networkCalls = new AtomicInteger();
        ShowMetadata meta = new ShowMetadata(
                "TMDB",
                280890,
                "The Lady's Companion",
                ShowType.SERIES,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                MatchConfidence.MANUAL_OVERRIDE);
        MetadataProvider delegate = new MetadataProvider() {
            @Override
            public Optional<ShowMetadata> findByTitle(String title) {
                return Optional.empty();
            }

            @Override
            public Optional<ShowMetadata> findById(String provider, String mediaType, long providerId) {
                networkCalls.incrementAndGet();
                return Optional.of(meta);
            }
        };
        CachingMetadataProvider caching = new CachingMetadataProvider(delegate, new FileMetadataCache(temp));

        assertThat(caching.findById("TMDB", "tv", 280890)).contains(meta);
        assertThat(caching.findById("TMDB", "tv", 280890)).contains(meta);
        assertThat(networkCalls.get()).isEqualTo(1);
    }
}

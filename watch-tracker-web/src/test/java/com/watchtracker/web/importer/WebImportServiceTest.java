package com.watchtracker.web.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.watchtracker.web.config.ImportProperties;
import com.watchtracker.web.persistence.EnrichedShowMongoRepository;
import com.watchtracker.web.persistence.MongoEnrichmentWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Exercises {@link WebImportService} against a real temp CSV, with {@link
 * EnrichedShowMongoRepository} mocked so no live MongoDB is needed.
 *
 * <p>Only covers the {@code withMetadata=false} path deliberately — {@code withMetadata=true}
 * would reach out to live TMDb/TVMaze via {@code MetadataProvider.from(MetadataProperties
 * .fromSystem())}, which isn't something a unit test should depend on. Provider-level behavior is
 * already covered by the fixture-based tests in {@code watch-tracker-core}; what this test owns is
 * the web-specific wiring around it (snapshot load, sticky-override merge, write, result summary).
 */
@ExtendWith(MockitoExtension.class)
class WebImportServiceTest {

    @Mock
    private EnrichedShowMongoRepository repository;

    @TempDir
    private Path tempDir;

    @Test
    void importsCsvAndSavesBuiltShowsWhenMetadataDisabled() throws IOException {
        Path csv = writeCsv("Dark: Episode 1,7/19/26\nDark: Episode 2,7/20/26\n");
        when(repository.findAll()).thenReturn(List.of());

        MongoEnrichmentWriter writer = new MongoEnrichmentWriter(repository);
        WebImportService service = new WebImportService(new ImportProperties(), repository, writer);

        ImportResult result = service.importFromCsv(csv, false);

        assertThat(result.eventCount()).isEqualTo(2);
        assertThat(result.showCount()).isEqualTo(1);
        assertThat(result.enrichedWithMetadata()).isZero();
        assertThat(result.reviewCount()).isZero();
        assertThat(result.message()).contains("no metadata");

        verify(repository).deleteAll();
        ArgumentCaptor<List<com.watchtracker.web.persistence.EnrichedShowDocument>> savedCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(savedCaptor.capture());
        assertThat(savedCaptor.getValue()).hasSize(1);
    }

    @Test
    void rejectsMissingCsvFile() {
        MongoEnrichmentWriter writer = new MongoEnrichmentWriter(repository);
        WebImportService service = new WebImportService(new ImportProperties(), repository, writer);

        assertThatThrownBy(() -> service.importFromCsv(tempDir.resolve("missing.csv"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void loadsPreviousSnapshotFromMongoRatherThanFile() throws IOException {
        // findAll is exercised via MongoPreviousEnrichmentSnapshot.load inside importFromCsv;
        // returning an empty list here is what proves the snapshot is read from Mongo (a real
        // file-backed snapshot would need no repository interaction at all).
        Path csv = writeCsv("Dark: Episode 1,7/19/26\n");
        when(repository.findAll()).thenReturn(List.of());

        MongoEnrichmentWriter writer = new MongoEnrichmentWriter(repository);
        WebImportService service = new WebImportService(new ImportProperties(), repository, writer);

        service.importFromCsv(csv, false);

        verify(repository).findAll();
    }

    private Path writeCsv(String rows) throws IOException {
        Path csv = tempDir.resolve("history.csv");
        Files.writeString(csv, "Title,Date\n" + rows);
        return csv;
    }
}

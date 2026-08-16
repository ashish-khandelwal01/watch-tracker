package com.watchtracker.web.importer;

import com.watchtracker.config.MetadataProperties;
import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.metadata.CachingMetadataProvider;
import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.metadata.FileMetadataCache;
import com.watchtracker.metadata.MetadataEnrichmentService;
import com.watchtracker.metadata.MetadataOverrideStore;
import com.watchtracker.metadata.MetadataProvider;
import com.watchtracker.metadata.PreviousEnrichmentSnapshot;
import com.watchtracker.metadata.ReviewQueueBuilder;
import com.watchtracker.metadata.RevokedOverrideStore;
import com.watchtracker.model.Show;
import com.watchtracker.model.WatchEvent;
import com.watchtracker.normalizer.TitleNormalizer;
import com.watchtracker.parser.NetflixCsvParser;
import com.watchtracker.service.ShowBuilderService;
import com.watchtracker.web.config.ImportProperties;
import com.watchtracker.web.persistence.EnrichedShowMongoRepository;
import com.watchtracker.web.persistence.MongoEnrichmentWriter;
import com.watchtracker.web.persistence.MongoPreviousEnrichmentSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the same core pipeline as the CLI (parse → normalize → build → enrich) but persists to
 * MongoDB instead of shows.json. The CLI module is not used or modified.
 */
@Service
public class WebImportService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WebImportService.class);

    private final ImportProperties importProperties;
    private final EnrichedShowMongoRepository repository;
    private final MongoEnrichmentWriter writer;

    public WebImportService(
            ImportProperties importProperties,
            EnrichedShowMongoRepository repository,
            MongoEnrichmentWriter writer) {
        this.importProperties = Objects.requireNonNull(importProperties);
        this.repository = Objects.requireNonNull(repository);
        this.writer = Objects.requireNonNull(writer);
    }

    /**
     * Imports a Netflix viewing-history CSV from a filesystem path and writes enriched shows to
     * Mongo.
     *
     * @param csvPath absolute or relative path visible to the server process
     * @param withMetadata when true, runs TMDb/TVMaze enrichment (needs token in env if using TMDb)
     */
    public ImportResult importFromCsv(Path csvPath, boolean withMetadata) {
        Objects.requireNonNull(csvPath, "csvPath must not be null");
        if (!Files.isRegularFile(csvPath)) {
            throw new IllegalArgumentException("CSV file not found: " + csvPath.toAbsolutePath());
        }

        var diagnostics = new NoOpDiagnosticsCollector();

        List<WatchEvent> events = new NetflixCsvParser()
                .parse(csvPath).stream()
                .map(new TitleNormalizer(diagnostics)::normalize)
                .toList();

        Path overridesFile = importProperties.getOverridesFile();
        Path revokedFile = importProperties.getRevokedFile();
        RevokedOverrideStore revoked =
                revokedFile != null ? RevokedOverrideStore.load(revokedFile) : RevokedOverrideStore.EMPTY;
        MetadataOverrideStore fileOverrides =
                overridesFile != null ? MetadataOverrideStore.load(overridesFile) : MetadataOverrideStore.EMPTY;
        if (overridesFile != null) {
            LOGGER.info("Loaded {} override(s) from {}", fileOverrides.size(), overridesFile.toAbsolutePath());
        } else {
            LOGGER.warn("No watchtracker.import.overrides-file configured — grouping/enrichment will differ from CLI");
        }

        PreviousEnrichmentSnapshot previous = MongoPreviousEnrichmentSnapshot.load(repository);
        var stickyFilter = revoked.filterSticky(previous.manualOverrides());
        MetadataOverrideStore overrides = fileOverrides.mergeWith(stickyFilter.kept());
        if (stickyFilter.kept().size() > 0) {
            LOGGER.info("Merged {} sticky MANUAL_OVERRIDE(s) from Mongo", stickyFilter.kept().size());
        }

        List<Show> shows = new ShowBuilderService(diagnostics, overrides).build(events);
        LOGGER.info("Imported {} events across {} shows from {}", events.size(), shows.size(), csvPath);

        List<EnrichedShow> enriched;
        if (withMetadata) {
            Path cacheDir = importProperties.getCacheDirectory() != null
                    ? importProperties.getCacheDirectory()
                    : Path.of(".watch-tracker", "metadata-cache");
            MetadataProvider provider = new CachingMetadataProvider(
                    MetadataProvider.from(MetadataProperties.fromSystem()), new FileMetadataCache(cacheDir));
            enriched = new MetadataEnrichmentService(provider, diagnostics, overrides, revoked)
                    .enrich(shows, events, previous);
        } else {
            enriched = shows.stream()
                    .map(show -> new EnrichedShow(show, java.util.Optional.empty(), java.util.Optional.empty()))
                    .toList();
        }

        writer.saveAll(enriched);

        int withMeta = (int) enriched.stream().filter(e -> e.metadata().isPresent()).count();
        int reviewCount = withMetadata ? ReviewQueueBuilder.build(enriched).size() : 0;

        String message = withMetadata
                ? "Import + metadata enrichment complete; results saved to MongoDB."
                : "Import complete (no metadata); results saved to MongoDB.";
        return new ImportResult(events.size(), shows.size(), withMeta, reviewCount, message);
    }
}

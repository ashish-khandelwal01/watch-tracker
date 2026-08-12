package com.watchtracker.cli;

import com.watchtracker.config.MetadataProperties;
import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.JsonDiagnosticsCollector;
import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.exporter.WatchHistoryExporter;
import com.watchtracker.metadata.CachingMetadataProvider;
import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.metadata.FileMetadataCache;
import com.watchtracker.metadata.JsonEnrichedShowSnapshot;
import com.watchtracker.metadata.MetadataEnrichmentService;
import com.watchtracker.metadata.MetadataOverrideStore;
import com.watchtracker.metadata.MetadataProvider;
import com.watchtracker.metadata.PreviousEnrichmentSnapshot;
import com.watchtracker.metadata.ReviewItem;
import com.watchtracker.metadata.ReviewQueueBuilder;
import com.watchtracker.model.Show;
import com.watchtracker.model.WatchEvent;
import com.watchtracker.normalizer.TitleNormalizer;
import com.watchtracker.parser.NetflixCsvParser;
import com.watchtracker.service.ShowBuilderService;
import java.nio.file.Path;
import java.util.List;

/** Command-line adapter for importing a Netflix viewing-history CSV. */
public final class WatchTrackerApplication {
    private WatchTrackerApplication() {}

    /**
     * Imports a CSV and optionally writes normalized show aggregates to JSON or CSV.
     *
     * @param arguments input path, optional output path, and optional enrichment flags
     */
    public static void main(String[] arguments) {
        if (arguments.length < 1) {
            usage();
            System.exit(2);
        }
        Arguments parsedArguments = Arguments.parse(arguments);

        DiagnosticsCollector diagnostics = parsedArguments.diagnosticsFile() != null
                ? new JsonDiagnosticsCollector()
                : new NoOpDiagnosticsCollector();

        List<WatchEvent> events = new NetflixCsvParser()
                .parse(parsedArguments.input()).stream()
                        .map(new TitleNormalizer(diagnostics)::normalize)
                        .toList();
        
        // Load overrides + prior MANUAL_OVERRIDE rows early so ShowBuilderService consolidates
        // under human-confirmed titles, and enrichment treats those confirms above search.
        // Order: sticky from shows.json first, then overrides.json on top (file wins on same key).
        MetadataOverrideStore overrides = MetadataOverrideStore.EMPTY;
        PreviousEnrichmentSnapshot previous = PreviousEnrichmentSnapshot.EMPTY;
        if (parsedArguments.output() != null && parsedArguments.useMetadata()) {
            Path output = parsedArguments.output();
            previous = JsonEnrichedShowSnapshot.load(output);
            Path overridesFile = parsedArguments.overridesFile() != null
                    ? parsedArguments.overridesFile()
                    : output.resolveSibling("overrides.json");
            MetadataOverrideStore fileOverrides = MetadataOverrideStore.load(overridesFile);
            int stickyCount = previous.manualOverrides().size();
            overrides = fileOverrides.mergeWith(previous.manualOverrides());
            if (fileOverrides.size() > 0) {
                System.out.printf("Loaded %d manual override(s) from %s.%n", fileOverrides.size(), overridesFile);
            }
            if (stickyCount > 0) {
                System.out.printf(
                        "Merged %d MANUAL_OVERRIDE confirmation(s) from previous %s (manual override above all else).%n",
                        stickyCount,
                        output.getFileName());
            }
        }

        List<Show> shows = new ShowBuilderService(diagnostics, overrides).build(events);
        System.out.printf("Imported %d watch events across %d shows.%n", events.size(), shows.size());
        if (parsedArguments.output() != null) {
            Path output = parsedArguments.output();
            WatchHistoryExporter exporter = new WatchHistoryExporter(diagnostics);
            if (output.getFileName()
                    .toString()
                    .toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".csv")) {
                exporter.exportCsv(shows, output);
            } else {
                if (parsedArguments.useMetadata()) {
                    List<EnrichedShow> enriched = new MetadataEnrichmentService(
                                    metadataProvider(parsedArguments.cacheDirectory()), diagnostics, overrides)
                            .enrich(shows, events, previous);

                    exporter.exportEnrichedJson(enriched, output);
                    System.out.printf("Enriched JSON written to %s.%n", output);

                    List<ReviewItem> reviewQueue = ReviewQueueBuilder.build(enriched);
                    Path reviewQueueFile = output.resolveSibling("review-queue.json");
                    exporter.exportReviewQueue(reviewQueue, reviewQueueFile);
                    System.out.printf(
                            "Review queue written to %s (%d of %d shows flagged for manual review).%n",
                            reviewQueueFile, reviewQueue.size(), enriched.size());
                } else {
                    exporter.exportJson(shows, output);
                }
            }
        }

        if (diagnostics instanceof JsonDiagnosticsCollector jsonCollector) {
            jsonCollector.write(parsedArguments.diagnosticsFile());
            System.out.printf("Diagnostics written to %s.%n", parsedArguments.diagnosticsFile());
        }
    }

    /** Creates the opt-in provider selected by metadata configuration. */
    private static MetadataProvider metadataProvider(Path cacheDirectory) {
        return new CachingMetadataProvider(
                MetadataProvider.from(MetadataProperties.fromSystem()), new FileMetadataCache(cacheDirectory));
    }

    /** Prints the command-line usage contract to standard error. */
    private static void usage() {
        System.err.println(
                "Usage: watch-tracker <netflix.csv> [shows.json|shows.csv] [--metadata] [--cache <directory>] "
                        + "[--overrides <file>] [--diagnostics [<directory>]]");
    }

    /** Immutable parsing result for the command-line interface. */
    private record Arguments(
            Path input, Path output, boolean useMetadata, Path cacheDirectory, Path diagnosticsFile, Path overridesFile) {
        private static final Path DEFAULT_CACHE = Path.of(".watch-tracker", "metadata-cache");
        private static final Path DEFAULT_DIAGNOSTICS = Path.of("build", "diagnostics");

        /** Parses validated positional arguments and supported options. */
        static Arguments parse(String[] values) {
            Path input = Path.of(values[0]);
            Path output = null;
            boolean metadata = false;
            Path cache = DEFAULT_CACHE;
            Path diagnostics = null;
            Path overrides = null;
            for (int index = 1; index < values.length; index++) {
                switch (values[index]) {
                    case "--metadata", "--tmdb" -> metadata = true;
                    case "--diagnostics" -> {
                        diagnostics = DEFAULT_DIAGNOSTICS;
                        if (index + 1 < values.length && !values[index + 1].startsWith("--")) {
                            diagnostics = Path.of(values[++index]);
                        }
                    }
                    case "--cache" -> {
                        if (++index == values.length) {
                            throw new IllegalArgumentException("--cache requires a directory");
                        }
                        cache = Path.of(values[index]);
                    }
                    case "--overrides" -> {
                        if (++index == values.length) {
                            throw new IllegalArgumentException("--overrides requires a file path");
                        }
                        overrides = Path.of(values[index]);
                    }
                    default -> {
                        if (values[index].startsWith("--") || output != null) {
                            throw new IllegalArgumentException("Unknown or duplicate argument: " + values[index]);
                        }
                        output = Path.of(values[index]);
                    }
                }
            }
            if (metadata
                    && output != null
                    && output.getFileName()
                            .toString()
                            .toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".csv")) {
                throw new IllegalArgumentException("Metadata enrichment is available only for JSON output");
            }
            return new Arguments(input, output, metadata, cache, diagnostics, overrides);
        }
    }
}

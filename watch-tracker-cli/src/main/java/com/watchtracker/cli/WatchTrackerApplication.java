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
import com.watchtracker.importer.TraktHistoryImporter;
import com.watchtracker.metadata.OverrideDataExporter;
import com.watchtracker.metadata.RevokedOverrideStore;
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

        TitleNormalizer normalizer = new TitleNormalizer(diagnostics);
        List<WatchEvent> events = new java.util.ArrayList<>(
                new NetflixCsvParser().parse(parsedArguments.input()).stream()
                        .map(normalizer::normalize)
                        .toList());

        // Trakt = optional multi-OTT history going forward (not a metadata catalogue for Netflix).
        if (parsedArguments.traktHistoryFile() != null) {
            List<WatchEvent> traktEvents = new TraktHistoryImporter()
                    .parseFile(parsedArguments.traktHistoryFile()).stream()
                    .map(normalizer::normalize)
                    .toList();
            events.addAll(traktEvents);
            System.out.printf(
                    "Merged %d Trakt history event(s) from %s.%n",
                    traktEvents.size(),
                    parsedArguments.traktHistoryFile());
        } else if (parsedArguments.useTrakt()) {
            List<WatchEvent> traktEvents = new TraktHistoryImporter()
                    .fetchHistory(System.getenv("TRAKT_CLIENT_ID"), System.getenv("TRAKT_ACCESS_TOKEN"))
                    .stream()
                    .map(normalizer::normalize)
                    .toList();
            events.addAll(traktEvents);
            System.out.printf("Merged %d Trakt history event(s) from API.%n", traktEvents.size());
        }
        events = List.copyOf(events);
        
        // Sticky from shows.json first (minus revoked), then overrides.json on top (file wins).
        MetadataOverrideStore overrides = MetadataOverrideStore.EMPTY;
        PreviousEnrichmentSnapshot previous = PreviousEnrichmentSnapshot.EMPTY;
        RevokedOverrideStore revoked = RevokedOverrideStore.EMPTY;
        if (parsedArguments.output() != null && parsedArguments.useMetadata()) {
            Path output = parsedArguments.output();
            previous = JsonEnrichedShowSnapshot.load(output);
            Path overridesFile = parsedArguments.overridesFile() != null
                    ? parsedArguments.overridesFile()
                    : output.resolveSibling("overrides.json");
            Path revokedFile = parsedArguments.revokedFile() != null
                    ? parsedArguments.revokedFile()
                    : output.resolveSibling("revoked-overrides.json");
            revoked = RevokedOverrideStore.load(revokedFile);
            MetadataOverrideStore fileOverrides = MetadataOverrideStore.load(overridesFile);
            var stickyFilter = revoked.filterSticky(previous.manualOverrides());
            overrides = fileOverrides.mergeWith(stickyFilter.kept());
            if (fileOverrides.size() > 0) {
                System.out.printf("Loaded %d manual override(s) from %s.%n", fileOverrides.size(), overridesFile);
            }
            if (stickyFilter.kept().size() > 0) {
                System.out.printf(
                        "Merged %d MANUAL_OVERRIDE confirmation(s) from previous %s (manual override above all else).%n",
                        stickyFilter.kept().size(),
                        output.getFileName());
            }
            if (stickyFilter.droppedCount() > 0) {
                System.out.printf(
                        "Dropped %d sticky confirmation(s) listed in %s.%n",
                        stickyFilter.droppedCount(),
                        revokedFile.getFileName());
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
                                    metadataProvider(parsedArguments.cacheDirectory()),
                                    diagnostics,
                                    overrides,
                                    revoked)
                            .enrich(shows, events, previous);

                    exporter.exportEnrichedJson(enriched, output);
                    System.out.printf("Enriched JSON written to %s.%n", output);

                    List<ReviewItem> reviewQueue = ReviewQueueBuilder.build(enriched);
                    Path reviewQueueFile = output.resolveSibling("review-queue.json");
                    exporter.exportReviewQueue(reviewQueue, reviewQueueFile);
                    System.out.printf(
                            "Review queue written to %s (%d of %d shows flagged for manual review).%n",
                            reviewQueueFile, reviewQueue.size(), enriched.size());

                    Path overrideDataFile = output.resolveSibling("override_data.json");
                    var overrideData = OverrideDataExporter.extract(enriched);
                    try {
                        new OverrideDataExporter().export(enriched, overrideDataFile);
                        System.out.printf(
                                "Override data (MANUAL_OVERRIDE source of truth) written to %s (%d entr%s).%n",
                                overrideDataFile,
                                overrideData.size(),
                                overrideData.size() == 1 ? "y" : "ies");
                    } catch (java.io.IOException exception) {
                        System.err.printf(
                                "Failed to write override data to %s: %s%n",
                                overrideDataFile,
                                exception.getMessage());
                    }
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
        Path cache = cacheDirectory == null
                ? Path.of(".watch-tracker", "metadata-cache").toAbsolutePath().normalize()
                : cacheDirectory.toAbsolutePath().normalize();
        System.out.printf("Metadata cache directory: %s%n", cache);
        return new CachingMetadataProvider(
                MetadataProvider.from(MetadataProperties.fromSystem()), new FileMetadataCache(cache));
    }

    /** Prints the command-line usage contract to standard error. */
    private static void usage() {
        System.err.println(
                "Usage: watch-tracker <netflix.csv> [shows.json|shows.csv] [--metadata] [--cache <directory>] "
                        + "[--overrides <file>] [--revoked <file>] [--trakt] [--trakt-history <file>] "
                        + "[--diagnostics [<directory>]]");
    }

    /** Immutable parsing result for the command-line interface. */
    private record Arguments(
            Path input,
            Path output,
            boolean useMetadata,
            Path cacheDirectory,
            Path diagnosticsFile,
            Path overridesFile,
            Path revokedFile,
            boolean useTrakt,
            Path traktHistoryFile) {
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
            Path revoked = null;
            boolean useTrakt = false;
            Path traktHistory = null;
            for (int index = 1; index < values.length; index++) {
                switch (values[index]) {
                    case "--metadata", "--tmdb" -> metadata = true;
                    case "--trakt" -> useTrakt = true;
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
                    case "--revoked" -> {
                        if (++index == values.length) {
                            throw new IllegalArgumentException("--revoked requires a file path");
                        }
                        revoked = Path.of(values[index]);
                    }
                    case "--trakt-history" -> {
                        if (++index == values.length) {
                            throw new IllegalArgumentException("--trakt-history requires a file path");
                        }
                        traktHistory = Path.of(values[index]);
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
            return new Arguments(
                    input, output, metadata, cache, diagnostics, overrides, revoked, useTrakt, traktHistory);
        }
    }
}

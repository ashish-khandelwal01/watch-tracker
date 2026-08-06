package com.watchtracker.cli;

import com.watchtracker.config.MetadataProperties;
import com.watchtracker.exporter.WatchHistoryExporter;
import com.watchtracker.metadata.CachingMetadataProvider;
import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.metadata.FileMetadataCache;
import com.watchtracker.metadata.JsonEnrichedShowSnapshot;
import com.watchtracker.metadata.MetadataEnrichmentService;
import com.watchtracker.metadata.MetadataProvider;
import com.watchtracker.metadata.PreviousEnrichmentSnapshot;
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
        List<WatchEvent> events = new NetflixCsvParser()
                .parse(parsedArguments.input()).stream()
                        .map(new TitleNormalizer()::normalize)
                        .toList();
        List<Show> shows = new ShowBuilderService().build(events);
        System.out.printf("Imported %d watch events across %d shows.%n", events.size(), shows.size());
        if (parsedArguments.output() != null) {
            Path output = parsedArguments.output();
            WatchHistoryExporter exporter = new WatchHistoryExporter();
            if (output.getFileName()
                    .toString()
                    .toLowerCase(java.util.Locale.ROOT)
                    .endsWith(".csv")) {
                exporter.exportCsv(shows, output);
            } else {
                if (parsedArguments.useMetadata()) {
                    PreviousEnrichmentSnapshot previous = JsonEnrichedShowSnapshot.load(output);
                    List<EnrichedShow> enriched = new MetadataEnrichmentService(
                                    metadataProvider(parsedArguments.cacheDirectory()))
                            .enrich(shows, events, previous);

                    exporter.exportEnrichedJson(enriched, output);
                    System.out.printf("Enriched JSON written to %s.%n", output);
                } else {
                    exporter.exportJson(shows, output);
                }
            }
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
                "Usage: watch-tracker <netflix.csv> [shows.json|shows.csv] [--metadata] [--cache <directory>]");
    }

    /** Immutable parsing result for the command-line interface. */
    private record Arguments(Path input, Path output, boolean useMetadata, Path cacheDirectory) {
        private static final Path DEFAULT_CACHE = Path.of(".watch-tracker", "metadata-cache");

        /** Parses validated positional arguments and supported options. */
        static Arguments parse(String[] values) {
            Path input = Path.of(values[0]);
            Path output = null;
            boolean metadata = false;
            Path cache = DEFAULT_CACHE;
            for (int index = 1; index < values.length; index++) {
                switch (values[index]) {
                    case "--metadata", "--tmdb" -> metadata = true;
                    case "--cache" -> {
                        if (++index == values.length) {
                            throw new IllegalArgumentException("--cache requires a directory");
                        }
                        cache = Path.of(values[index]);
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
            return new Arguments(input, output, metadata, cache);
        }
    }
}

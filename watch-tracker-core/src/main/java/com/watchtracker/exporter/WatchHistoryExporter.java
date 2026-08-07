package com.watchtracker.exporter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.DiagnosticsStage;
import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.metadata.EnrichedShow;
import com.watchtracker.model.Show;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

/** Exports show aggregates as portable JSON. */
public final class WatchHistoryExporter {
    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DiagnosticsCollector diagnostics;

    public WatchHistoryExporter() {
        this(new NoOpDiagnosticsCollector());
    }

    public WatchHistoryExporter(DiagnosticsCollector diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    /**
     * Writes aggregates as a JSON array.
     *
     * @param shows aggregates to export
     * @param output destination JSON file
     */
    public void exportJson(List<Show> shows, Path output) {
        Objects.requireNonNull(shows, "shows must not be null");
        Objects.requireNonNull(output, "output must not be null");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), shows);
            diagnostics.success(
                    DiagnosticsStage.EXPORTER,
                    "Export",
                    "JSON export successful",
                    null,
                    java.util.Map.of("count", shows.size(), "path", output.toString()));
        } catch (IOException exception) {
            diagnostics.failure(
                    DiagnosticsStage.EXPORTER, "Export", "Failed to export JSON: " + exception.getMessage());
            throw new IllegalStateException("Unable to export JSON to " + output, exception);
        }
    }

    /**
     * Returns aggregates as JSON for adapters that own their destination.
     *
     * @param shows aggregates to serialize
     * @return JSON array representation
     */
    public String toJson(List<Show> shows) {
        try {
            return mapper.writeValueAsString(shows);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize shows", exception);
        }
    }

    /**
     * Writes show aggregates together with optional external metadata as a JSON array.
     *
     * @param shows enriched aggregates to export
     * @param output destination JSON file
     */
    public void exportEnrichedJson(List<EnrichedShow> shows, Path output) {
        Objects.requireNonNull(shows, "shows must not be null");
        Objects.requireNonNull(output, "output must not be null");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), shows);
            diagnostics.success(
                    DiagnosticsStage.EXPORTER,
                    "Export",
                    "Enriched JSON export successful",
                    null,
                    java.util.Map.of("count", shows.size(), "path", output.toString()));
        } catch (IOException exception) {
            diagnostics.failure(
                    DiagnosticsStage.EXPORTER, "Export", "Failed to export enriched JSON: " + exception.getMessage());
            throw new IllegalStateException("Unable to export enriched JSON to " + output, exception);
        }
    }

    /**
     * Writes a compact, spreadsheet-friendly CSV representation of show aggregates.
     *
     * @param shows aggregates to export
     * @param output destination CSV file
     */
    public void exportCsv(List<Show> shows, Path output) {
        Objects.requireNonNull(shows, "shows must not be null");
        Objects.requireNonNull(output, "output must not be null");
        try (CSVPrinter printer = new CSVPrinter(
                Files.newBufferedWriter(output),
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader(
                                "Title",
                                "First Watched",
                                "Last Watched",
                                "Highest Season",
                                "Highest Episode",
                                "Watch Count")
                        .get())) {
            for (Show show : shows) {
                printer.printRecord(
                        show.title(),
                        show.firstWatched(),
                        show.lastWatched(),
                        show.highestSeasonSeen(),
                        show.highestEpisodeSeen(),
                        show.watchCount());
            }
            diagnostics.success(
                    DiagnosticsStage.EXPORTER,
                    "Export",
                    "CSV export successful",
                    null,
                    java.util.Map.of("count", shows.size(), "path", output.toString()));
        } catch (IOException exception) {
            diagnostics.failure(DiagnosticsStage.EXPORTER, "Export", "Failed to export CSV: " + exception.getMessage());
            throw new IllegalStateException("Unable to export CSV to " + output, exception);
        }
    }
}

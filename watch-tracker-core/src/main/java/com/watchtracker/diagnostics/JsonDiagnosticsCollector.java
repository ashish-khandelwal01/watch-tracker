package com.watchtracker.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class JsonDiagnosticsCollector implements DiagnosticsCollector {

    private final List<DiagnosticsEntry> entries = new ArrayList<>();
    private final ObjectMapper mapper =
            new ObjectMapper().registerModule(new JavaTimeModule()).registerModule(new Jdk8Module())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public void record(DiagnosticsEntry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    public List<DiagnosticsEntry> getEntries() {
        return entries;
    }

    public void write(Path file) {
        if (file == null) {
            return;
        }

        // Ensure parent directory or target directory exists
        Path dir;
        if (Files.isDirectory(file) || !file.getFileName().toString().contains(".")) {
            dir = file;
        } else {
            dir = file.getParent();
        }

        if (dir != null) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to create diagnostics directory: " + dir, e);
            }
        }

        // Group entries by show title (ignoring null titles)
        Map<String, List<DiagnosticsEntry>> grouped = entries.stream()
                .filter(e -> e.title() != null && !e.title().isBlank())
                .collect(Collectors.groupingBy(DiagnosticsEntry::title));

        // Create individual show reports
        for (Map.Entry<String, List<DiagnosticsEntry>> entry : grouped.entrySet()) {
            String showName = entry.getKey();
            List<StageReport> stages =
                    entry.getValue().stream().map(StageReport::from).toList();
            ShowDiagnostics showDiag = new ShowDiagnostics(showName, stages);

            // File name: Money Heist.json, etc. (sanitize any illegal filename chars just in case)
            String safeName = showName.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json";
            Path showFile = (dir != null) ? dir.resolve(safeName) : Path.of(safeName);

            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(showFile.toFile(), showDiag);
            } catch (IOException e) {
                // Keep moving, but log/warn
                System.err.println(
                        "Failed to write diagnostics for " + showName + " to " + showFile + ": " + e.getMessage());
            }
        }

        // If 'file' is actually a JSON file, write the whole grouped report to it as well
        if (file.getFileName().toString().endsWith(".json")) {
            List<ShowDiagnostics> allReports = grouped.entrySet().stream()
                    .map(entry -> new ShowDiagnostics(
                            entry.getKey(),
                            entry.getValue().stream().map(StageReport::from).toList()))
                    .toList();
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), allReports);
            } catch (IOException e) {
                System.err.println("Failed to write main diagnostics file to " + file + ": " + e.getMessage());
            }
        }
    }
}

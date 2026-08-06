package com.watchtracker.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DiagnosticsReport {

    private final Map<String, List<DiagnosticsEntry>> reports = new LinkedHashMap<>();

    public void addEntry(DiagnosticsEntry entry) {
        if (entry == null || entry.title() == null) {
            return;
        }
        reports.computeIfAbsent(entry.title(), k -> new ArrayList<>()).add(entry);
    }

    public Map<String, List<DiagnosticsEntry>> getReports() {
        return reports;
    }
}

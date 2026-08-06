package com.watchtracker.diagnostics;

public class NoOpDiagnosticsCollector implements DiagnosticsCollector {

    @Override
    public void record(DiagnosticsEntry entry) {
        // intentionally empty
    }
}

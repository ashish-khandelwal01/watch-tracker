package com.watchtracker.diagnostics;

public interface DiagnosticsCollector {

    void record(DiagnosticsEntry entry);

    default void info(DiagnosticsStage stage, String title, String message) {
        record(new DiagnosticsEntry(stage, title, message, null, null));
    }

    default void success(DiagnosticsStage stage, String title, Object input, Object output) {
        record(new DiagnosticsEntry(stage, title, null, input, output));
    }

    default void success(DiagnosticsStage stage, String title, String message, Object input, Object output) {
        record(new DiagnosticsEntry(stage, title, message, input, output));
    }

    default void failure(DiagnosticsStage stage, String title, String reason) {
        record(new DiagnosticsEntry(stage, title, reason, null, null));
    }

    default void failure(DiagnosticsStage stage, String title, String reason, Object input) {
        record(new DiagnosticsEntry(stage, title, reason, input, null));
    }

    default void warning(DiagnosticsStage stage, String title, String message) {
        record(new DiagnosticsEntry(stage, title, message, null, null));
    }
}

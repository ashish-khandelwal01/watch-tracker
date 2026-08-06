package com.watchtracker.diagnostics;

public record DiagnosticsEntry(DiagnosticsStage stage, String title, String message, Object input, Object output) {}

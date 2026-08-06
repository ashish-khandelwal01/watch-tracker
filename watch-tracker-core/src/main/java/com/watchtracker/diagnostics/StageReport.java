package com.watchtracker.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record StageReport(DiagnosticsStage stage, String message, String reason, Object input, Object output) {
    public static StageReport from(DiagnosticsEntry entry) {
        String msg = entry.message();
        String rsn = null;
        if (entry.stage() == DiagnosticsStage.PROGRESS_BUILDER) {
            if ("No numbered event".equals(msg)) {
                msg = "Progress not created";
                rsn = "No numbered event";
            }
        }
        return new StageReport(entry.stage(), msg, rsn, entry.input(), entry.output());
    }
}

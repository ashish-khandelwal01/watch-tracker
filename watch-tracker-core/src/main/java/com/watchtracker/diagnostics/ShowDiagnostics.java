package com.watchtracker.diagnostics;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ShowDiagnostics(String show, List<StageReport> stages) {}

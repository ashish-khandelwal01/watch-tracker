package com.watchtracker.service;

import com.watchtracker.diagnostics.DiagnosticsCollector;
import com.watchtracker.diagnostics.DiagnosticsStage;
import com.watchtracker.diagnostics.NoOpDiagnosticsCollector;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import com.watchtracker.model.WatchEvent;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds non-speculative show aggregates from normalized watch events. */
public final class ShowBuilderService {
    private final DiagnosticsCollector diagnostics;

    public ShowBuilderService() {
        this(new NoOpDiagnosticsCollector());
    }

    public ShowBuilderService(DiagnosticsCollector diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics must not be null");
    }

    /**
     * Groups events into one show for each canonical title.
     *
     * @param events normalized watch events
     * @return aggregates in first-seen canonical-title order
     */
    public List<Show> build(List<WatchEvent> events) {
        Objects.requireNonNull(events, "events must not be null");
        Map<String, List<WatchEvent>> groups = events.stream()
                .filter(WatchEvent::parsed)
                .filter(event ->
                        event.canonicalShow() != null && !event.canonicalShow().isBlank())
                .collect(Collectors.groupingBy(WatchEvent::canonicalShow, LinkedHashMap::new, Collectors.toList()));
        return groups.entrySet().stream()
                .map(entry -> aggregate(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Produces one aggregate from events known to share a canonical title. */
    private Show aggregate(String title, List<WatchEvent> events) {
        LocalDateTime first = events.stream()
                .map(WatchEvent::watchedAt)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        LocalDateTime last = events.stream()
                .map(WatchEvent::watchedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        Set<String> aliases = events.stream().map(WatchEvent::rawTitle).collect(Collectors.toUnmodifiableSet());
        Integer highestSeason = highest(events, WatchEvent::season);
        Integer highestEpisode = highest(events, WatchEvent::episode);

        Map<String, Object> output = new java.util.LinkedHashMap<>();
        if (highestSeason != null) {
            output.put("highestSeasonSeen", highestSeason);
        }
        if (highestEpisode != null) {
            output.put("highestEpisodeSeen", highestEpisode);
        }

        diagnostics.success(DiagnosticsStage.SHOW_BUILDER, title, null, output);

        return new Show(
                UUID.nameUUIDFromBytes(title.getBytes(StandardCharsets.UTF_8)),
                title,
                aliases,
                ShowType.UNKNOWN,
                first,
                last,
                highestSeason,
                highestEpisode,
                events.size());
    }

    /** Computes the largest non-null value selected from a group of events. */
    private static Integer highest(List<WatchEvent> events, java.util.function.Function<WatchEvent, Integer> selector) {
        return events.stream()
                .map(selector)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }
}

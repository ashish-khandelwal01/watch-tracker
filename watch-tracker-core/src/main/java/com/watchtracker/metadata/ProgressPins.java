package com.watchtracker.metadata;

import com.watchtracker.model.CompletionStatus;
import com.watchtracker.model.Show;
import com.watchtracker.model.ShowType;
import java.util.Objects;
import java.util.Optional;

/**
 * Applies human progress pins from {@link MetadataOverride}: season + episode only.
 *
 * <p>Completion status is always derived from {@link ShowMetadata#totalEpisodes()} via
 * {@link ProgressResolver#completionStatus(int, Optional)} — never from the override file.
 * Pins are ignored when metadata is missing.
 */
public final class ProgressPins {
    private ProgressPins() {}

    /**
     * @param show current show aggregate (for watchCount as distinct-episode proxy)
     * @param override matching override, if any
     * @param metadata resolved metadata; required for a pin
     * @return pinned progress, or empty when no pin / no metadata / invalid season-episode
     */
    public static Optional<WatchProgress> fromOverride(
            Show show, Optional<MetadataOverride> override, Optional<ShowMetadata> metadata) {
        Objects.requireNonNull(show, "show must not be null");
        if (metadata.isEmpty() || override.isEmpty() || !override.get().hasProgressPin()) {
            return Optional.empty();
        }
        MetadataOverride pin = override.get();
        int season = pin.progressSeason();
        int episode = pin.progressEpisode();
        if (season < 1 || episode < 1) {
            return Optional.empty();
        }

        ShowMetadata md = metadata.get();
        if (md.type() == ShowType.MOVIE) {
            return Optional.of(new WatchProgress(
                    ShowType.MOVIE, null, null, md.provider(), CompletionStatus.COMPLETED));
        }

        ShowType type = md.type() != null && md.type() != ShowType.UNKNOWN ? md.type() : ShowType.SERIES;
        int distinctWatched = Math.max(1, show.watchCount());
        CompletionStatus status = ProgressResolver.completionStatus(distinctWatched, metadata);
        return Optional.of(new WatchProgress(type, season, episode, "MANUAL_OVERRIDE", status));
    }
}

package com.watchtracker.metadata;

import com.watchtracker.model.Show;
import java.util.Objects;
import java.util.Optional;

/**
 * A show aggregate paired with optional provider metadata.
 *
 * @param show imported show aggregate
 * @param metadata metadata supplied by an external provider
 * @param progress highest provider-resolved episode position
 */
public record EnrichedShow(Show show, Optional<ShowMetadata> metadata, Optional<WatchProgress> progress) {
    /** Creates an enrichment result. */
    public EnrichedShow {
        Objects.requireNonNull(show, "show must not be null");
        metadata = metadata == null ? Optional.empty() : metadata;
        progress = progress == null ? Optional.empty() : progress;
    }

    /**
     * Creates an enrichment result with no resolved episode progress.
     *
     * @param show imported show aggregate
     * @param metadata metadata supplied by an external provider
     */
    public EnrichedShow(Show show, Optional<ShowMetadata> metadata) {
        this(show, metadata, Optional.empty());
    }
}

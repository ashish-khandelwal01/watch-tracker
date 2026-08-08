package com.watchtracker.model;

import java.util.Objects;
import java.util.UUID;

/**
 * User-owned metadata that is deliberately separate from imported viewing history.
 *
 * @param showId associated show identifier
 * @param status user-declared watch status. This is set only by explicit user action and is never
 *     inferred or overwritten from {@link com.watchtracker.metadata.WatchProgress}'s mechanically
 *     derived {@link CompletionStatus} — the two answer different questions ("what the user
 *     wants" versus "what happened") and are allowed to disagree. See {@link CompletionStatus} for
 *     why.
 * @param completedOutsideNetflix whether completion occurred outside Netflix
 * @param reasonStopped optional reason the user stopped watching
 * @param recommendation user-facing recommendation
 * @param priority user-assigned priority
 * @param rating optional five-point rating
 * @param favorite whether the title is a favorite
 * @param notes optional free-form notes
 */
public record UserShow(
        UUID showId,
        WatchStatus status,
        boolean completedOutsideNetflix,
        String reasonStopped,
        Recommendation recommendation,
        Priority priority,
        Integer rating,
        boolean favorite,
        String notes) {
    /** Creates user metadata and validates an optional five-point rating. */
    public UserShow {
        Objects.requireNonNull(showId, "showId must not be null");
        status = status == null ? WatchStatus.UNKNOWN : status;
        recommendation = recommendation == null ? Recommendation.NONE : recommendation;
        priority = priority == null ? Priority.MEDIUM : priority;
        if (rating != null && (rating < 1 || rating > 5)) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
    }
}
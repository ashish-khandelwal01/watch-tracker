package com.watchtracker.model;

/**
 * A user-declared intent about a show, entirely independent of how much of it has actually been
 * watched.
 *
 * <p>This lives exclusively on {@link UserShow} and is set only by explicit user action — for
 * example, through a future Spring Boot/UI layer. Imported watch events and provider metadata
 * never infer or assign this value; that mechanical, data-derived judgment belongs to {@link
 * CompletionStatus} on {@code WatchProgress} instead. See {@link CompletionStatus} for the full
 * reasoning and examples of how the two are allowed to diverge.
 *
 * <p>In short: nothing here should ever be set from a {@link CompletionStatus} value, and nothing
 * in {@link CompletionStatus} should ever be set from this enum. A user finishing every episode of
 * a show does not mean they want it marked {@link #COMPLETED} here — they might prefer {@link
 * #DROPPED} because they hated the ending. That choice belongs to the user alone.
 */
public enum WatchStatus {
    /** The user has not set a status. */
    UNKNOWN,
    /** The user is actively watching. */
    WATCHING,
    /** The user intends to watch next. */
    WATCH_NEXT,
    /** The user has deferred the title. */
    FINISH_LATER,
    /** The user declares the title complete. */
    COMPLETED,
    /** The user has stopped watching. */
    DROPPED
}
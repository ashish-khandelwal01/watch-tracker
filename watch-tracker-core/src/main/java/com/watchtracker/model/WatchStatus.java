package com.watchtracker.model;

/** A user-declared progress state; imported events never infer this state. */
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

package com.watchtracker.model;

/** A user-facing recommendation state. */
public enum Recommendation {
    /** Continue watching the title. */
    CONTINUE,
    /** Keep the title for later. */
    WATCH_LATER,
    /** Stop recommending the title. */
    DROP,
    /** Recommend watching the title again. */
    REWATCH,
    /** No recommendation has been made. */
    NONE
}

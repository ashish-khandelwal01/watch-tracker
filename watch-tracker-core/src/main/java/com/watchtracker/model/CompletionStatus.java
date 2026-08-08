package com.watchtracker.model;

/**
 * A mechanically derived completion state for a show's resolved watch progress.
 *
 * <p>This is computed purely from data — a provider's known total episode count compared against
 * the highest resolved watch position — never from user intent. It answers "how much of this has
 * been watched," as a fact, nothing more.
 *
 * <p>This is deliberately kept separate from {@link WatchStatus}, which captures the user's own,
 * inherently subjective intent (dropped, saved for later, actively watching, etc.) and lives on
 * {@link UserShow} instead. That distinction matters because the two can legitimately diverge:
 *
 * <ul>
 *   <li>A show can be mechanically {@link #COMPLETED} while the user has separately marked it
 *       {@code DROPPED} — they watched every episode but decided they didn't like it.
 *   <li>A show can be {@link #IN_PROGRESS} while the user has marked it {@code WATCH_NEXT} — they
 *       haven't consciously started it, but a handful of episodes were in fact watched (an
 *       autoplay carry-on, a preview, a rewatch of episode one from years ago).
 *   <li>A show can be {@link #UNKNOWN} (no provider episode count available to compare against)
 *       while the user has confidently marked it {@code COMPLETED} from their own memory.
 * </ul>
 *
 * <p>Nothing in this codebase should assign a {@link WatchStatus} value from a {@code
 * CompletionStatus}, or the reverse. They answer different questions — "what happened" versus
 * "what the user wants" — and only the second one is meaningful to surface as a default or a
 * suggestion once a UI exists; it should never be silently overwritten by the first.
 */
public enum CompletionStatus {
    /**
     * Not enough information exists to compare watched progress against a total — for example, no
     * provider episode count is known for this show yet.
     */
    UNKNOWN,
    /** The highest resolved watch position is short of the provider's known episode total. */
    IN_PROGRESS,
    /** The highest resolved watch position has reached the provider's known episode total. */
    COMPLETED
}

package com.watchtracker.web.importer;

/** Lifecycle state of a background import job. See {@link ImportJob}. */
public enum ImportJobState {
    RUNNING,
    COMPLETED,
    FAILED
}

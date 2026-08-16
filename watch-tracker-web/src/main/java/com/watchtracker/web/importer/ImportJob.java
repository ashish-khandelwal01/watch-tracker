package com.watchtracker.web.importer;

import java.time.Instant;
import java.util.Optional;

/**
 * Immutable snapshot of a background import job.
 *
 * <p>{@link ImportJobStore} holds the mutable, in-memory map of these; each state transition
 * replaces the stored value with a new instance rather than mutating one in place, so a caller
 * reading a job never observes a half-updated result/error pair.
 *
 * @param id opaque job identifier, used in the {@code /import/status/{id}} URL
 * @param state current lifecycle state
 * @param result the run's summary, present only when {@code state == COMPLETED}
 * @param errorMessage the failure's message, present only when {@code state == FAILED}
 * @param submittedAt when the job was accepted, for display on the status page
 */
public record ImportJob(
        String id,
        ImportJobState state,
        Optional<ImportResult> result,
        Optional<String> errorMessage,
        Instant submittedAt) {

    static ImportJob running(String id) {
        return new ImportJob(id, ImportJobState.RUNNING, Optional.empty(), Optional.empty(), Instant.now());
    }

    ImportJob completed(ImportResult result) {
        return new ImportJob(id, ImportJobState.COMPLETED, Optional.of(result), Optional.empty(), submittedAt);
    }

    ImportJob failed(String message) {
        return new ImportJob(id, ImportJobState.FAILED, Optional.empty(), Optional.of(message), submittedAt);
    }
}

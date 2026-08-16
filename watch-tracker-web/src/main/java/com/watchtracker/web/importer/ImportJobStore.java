package com.watchtracker.web.importer;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of import jobs, keyed by job id.
 *
 * <p>Intentionally not persisted — a job is a transient "is this import still running" signal for
 * the status page, not a durable record; the durable result of a successful run is whatever ends
 * up in the {@code enriched_shows} Mongo collection. A restart losing in-flight job status is
 * acceptable since the import itself would need to be re-triggered anyway after a restart.
 *
 * <p>Also owns the single-job-at-a-time guard: {@link #tryStart()} uses an {@link AtomicBoolean}
 * so at most one import can be {@code RUNNING} at once, matching the single-thread {@code
 * importTaskExecutor} in {@code AsyncConfig} and protecting {@code MongoEnrichmentWriter}'s
 * replace-the-collection write from a concurrent second run.
 */
@Component
public class ImportJobStore {
    private final Map<String, ImportJob> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);

    /**
     * Attempts to reserve the single import slot and register a new {@code RUNNING} job.
     *
     * @return the new job's id, or empty if an import is already running
     */
    public Optional<String> tryStart() {
        if (!busy.compareAndSet(false, true)) {
            return Optional.empty();
        }
        String id = UUID.randomUUID().toString();
        jobs.put(id, ImportJob.running(id));
        return Optional.of(id);
    }

    /**
     * Records a successful run and releases the import slot.
     *
     * @param id the job id returned by {@link #tryStart()}
     * @param result the run's summary
     */
    public void complete(String id, ImportResult result) {
        jobs.computeIfPresent(id, (jobId, job) -> job.completed(result));
        busy.set(false);
    }

    /**
     * Records a failed run and releases the import slot.
     *
     * @param id the job id returned by {@link #tryStart()}
     * @param message a human-readable failure summary
     */
    public void fail(String id, String message) {
        jobs.computeIfPresent(id, (jobId, job) -> job.failed(message));
        busy.set(false);
    }

    /**
     * Looks up a job's current status.
     *
     * @param id the job id
     * @return the job, or empty if no job with that id was ever registered (e.g. server restarted)
     */
    public Optional<ImportJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }
}

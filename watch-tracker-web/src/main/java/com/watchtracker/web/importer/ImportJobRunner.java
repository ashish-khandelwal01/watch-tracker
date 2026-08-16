package com.watchtracker.web.importer;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs {@link WebImportService#importFromCsv} on the {@code importTaskExecutor} background thread
 * and reports the outcome back to {@link ImportJobStore}.
 *
 * <p>Split out from {@link WebImportService} so that service stays a plain, synchronously-testable
 * unit (see {@code WebImportServiceTest}) with no knowledge of jobs or async execution; this class
 * is the only place those two concerns meet.
 */
@Component
public class ImportJobRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImportJobRunner.class);

    private final WebImportService importService;
    private final ImportJobStore jobStore;

    public ImportJobRunner(WebImportService importService, ImportJobStore jobStore) {
        this.importService = importService;
        this.jobStore = jobStore;
    }

    /**
     * Runs the import in the background and updates {@code jobId}'s status on completion.
     *
     * @param jobId the id reserved by {@link ImportJobStore#tryStart()}
     * @param csvPath path to the Netflix history CSV
     * @param withMetadata whether to run TMDb/TVMaze enrichment
     */
    @Async("importTaskExecutor")
    public void runImport(String jobId, Path csvPath, boolean withMetadata) {
        try {
            ImportResult result = importService.importFromCsv(csvPath, withMetadata);
            jobStore.complete(jobId, result);
        } catch (RuntimeException exception) {
            LOGGER.warn("Import job {} failed", jobId, exception);
            jobStore.fail(jobId, exception.getMessage() != null ? exception.getMessage() : exception.toString());
        }
    }
}

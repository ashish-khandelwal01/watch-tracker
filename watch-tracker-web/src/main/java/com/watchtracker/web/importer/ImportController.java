package com.watchtracker.web.importer;

import java.nio.file.Path;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Form + POST to run a CSV import through core and into Mongo (CLI stays separate).
 *
 * <p>The actual import runs in the background via {@link ImportJobRunner} — enrichment can make
 * live TMDb/TVMaze calls, so it must not run inside this controller's request thread. {@code POST
 * /import} only reserves a job slot and redirects to {@code GET /import/status/{jobId}}, which the
 * browser polls (via meta-refresh) until the job finishes.
 */
@Controller
public class ImportController {
    private final ImportJobRunner jobRunner;
    private final ImportJobStore jobStore;

    public ImportController(ImportJobRunner jobRunner, ImportJobStore jobStore) {
        this.jobRunner = jobRunner;
        this.jobStore = jobStore;
    }

    @GetMapping("/import")
    public String form(Model model) {
        model.addAttribute("withMetadata", true);
        return "import";
    }

    @PostMapping("/import")
    public String run(
            @RequestParam("csvPath") String csvPath,
            @RequestParam(value = "withMetadata", required = false) String withMetadataFlag,
            Model model) {
        boolean withMetadata = withMetadataFlag != null;
        if (csvPath == null || csvPath.isBlank()) {
            model.addAttribute("error", "CSV path is required.");
            model.addAttribute("withMetadata", withMetadata);
            return "import";
        }

        var jobId = jobStore.tryStart();
        if (jobId.isEmpty()) {
            model.addAttribute(
                    "error", "An import is already running. Wait for it to finish before starting another.");
            model.addAttribute("csvPath", csvPath);
            model.addAttribute("withMetadata", withMetadata);
            return "import";
        }

        jobRunner.runImport(jobId.get(), Path.of(csvPath.trim()), withMetadata);
        return "redirect:/import/status/" + jobId.get();
    }

    @GetMapping("/import/status/{jobId}")
    public String status(@PathVariable String jobId, Model model) {
        var job = jobStore.find(jobId);
        if (job.isEmpty()) {
            model.addAttribute("notFound", true);
            return "import-status";
        }
        model.addAttribute("job", job.get());
        return "import-status";
    }
}

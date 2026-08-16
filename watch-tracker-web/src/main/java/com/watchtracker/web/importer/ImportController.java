package com.watchtracker.web.importer;

import java.nio.file.Path;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Form + POST to run a CSV import through core and into Mongo (CLI stays separate). */
@Controller
public class ImportController {
    private final WebImportService importService;

    public ImportController(WebImportService importService) {
        this.importService = importService;
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
            RedirectAttributes redirectAttributes,
            Model model) {
        boolean withMetadata = withMetadataFlag != null;
        if (csvPath == null || csvPath.isBlank()) {
            model.addAttribute("error", "CSV path is required.");
            model.addAttribute("withMetadata", withMetadata);
            return "import";
        }
        try {
            ImportResult result = importService.importFromCsv(Path.of(csvPath.trim()), withMetadata);
            redirectAttributes.addFlashAttribute("importResult", result);
            return "redirect:/";
        } catch (RuntimeException exception) {
            model.addAttribute("error", exception.getMessage());
            model.addAttribute("csvPath", csvPath);
            model.addAttribute("withMetadata", withMetadata);
            return "import";
        }
    }
}

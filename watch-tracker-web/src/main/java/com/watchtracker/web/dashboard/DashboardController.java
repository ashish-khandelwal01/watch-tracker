package com.watchtracker.web.dashboard;

import com.watchtracker.web.persistence.EnrichedShowDocument;
import com.watchtracker.web.persistence.EnrichedShowMongoRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Library list with posters, progress, and CLI-parity review filter. */
@Controller
public class DashboardController {
    private final EnrichedShowMongoRepository repository;
    private final ShowViewFactory showViewFactory;

    public DashboardController(EnrichedShowMongoRepository repository, ShowViewFactory showViewFactory) {
        this.repository = repository;
        this.showViewFactory = showViewFactory;
    }

    @GetMapping("/")
    public String dashboard(
            @RequestParam(value = "filter", defaultValue = "all") String filter, Model model) {
        List<EnrichedShowDocument> allDocs = repository.findAllByOrderByTitleAsc();
        List<ShowView> all = showViewFactory.fromDocuments(allDocs);

        String normalized = filter == null ? "all" : filter.trim().toLowerCase(Locale.ROOT);
        List<ShowView> shows =
                switch (normalized) {
                    case "review" -> all.stream().filter(ShowView::needsReview).toList();
                    case "manual" -> all.stream()
                            .filter(s -> "MANUAL_OVERRIDE".equals(s.matchConfidence()))
                            .toList();
                    default -> {
                        normalized = "all";
                        yield all;
                    }
                };

        long needsReviewCount = all.stream().filter(ShowView::needsReview).count();
        long manualCount =
                all.stream().filter(s -> "MANUAL_OVERRIDE".equals(s.matchConfidence())).count();

        model.addAttribute("shows", shows);
        model.addAttribute("totalCount", all.size());
        model.addAttribute("needsReviewCount", needsReviewCount);
        model.addAttribute("manualCount", manualCount);
        model.addAttribute("filter", normalized);
        return "dashboard";
    }
}

package com.watchtracker.web.dashboard;

import com.watchtracker.web.persistence.EnrichedShowDocument;
import com.watchtracker.web.persistence.EnrichedShowMongoRepository;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Read-only overview of what's currently persisted in MongoDB.
 *
 * <p>This is the "thin web UI for review" from the README roadmap, in its earliest useful form:
 * list everything, flag what looks unresolved. It intentionally works off the indexed document
 * fields ({@code title}, {@code aliases}, {@code matchConfidence}) rather than deserializing the
 * full {@code EnrichedShow} payload — the listing doesn't need episode progress or provider
 * metadata detail, so there's no reason to pay for parsing it. A per-show detail page that does
 * need that detail would go through {@code MongoPreviousEnrichmentSnapshot} instead.
 *
 * <p>Not a replacement for {@code review-queue.json}: this has no visibility into missing progress
 * or the specific reason a show needs attention, only whether its stored match confidence looks
 * uncertain. See {@link EnrichedShowMongoRepository} for the exact (cruder) definition in use
 * here.
 */
@Controller
public class DashboardController {
    private static final List<String> CONFIDENT_MATCH_TYPES = List.of("EXACT", "MANUAL_OVERRIDE");

    private final EnrichedShowMongoRepository repository;

    public DashboardController(EnrichedShowMongoRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/")
    public String index(Model model) {
        List<EnrichedShowDocument> shows = repository.findAllByOrderByTitleAsc();
        List<EnrichedShowDocument> needsReview =
                repository.findByMatchConfidenceNotInOrMatchConfidenceIsNull(CONFIDENT_MATCH_TYPES);

        model.addAttribute("totalCount", shows.size());
        model.addAttribute("needsReview", needsReview);
        model.addAttribute("shows", shows);
        return "dashboard";
    }
}

package com.watchtracker.web.dashboard;

import com.watchtracker.web.persistence.EnrichedShowMongoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/** Single-show detail: poster, metadata, progress, aliases. */
@Controller
public class ShowDetailController {
    private final EnrichedShowMongoRepository repository;
    private final ShowViewFactory showViewFactory;

    public ShowDetailController(EnrichedShowMongoRepository repository, ShowViewFactory showViewFactory) {
        this.repository = repository;
        this.showViewFactory = showViewFactory;
    }

    @GetMapping("/shows/{id}")
    public String detail(@PathVariable String id, Model model) {
        var document = repository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Show not found"));
        model.addAttribute("show", showViewFactory.fromDocument(document));
        return "show-detail";
    }
}

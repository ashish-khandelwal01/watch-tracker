package com.watchtracker.web.dashboard;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.watchtracker.web.persistence.EnrichedShowDocument;
import com.watchtracker.web.persistence.EnrichedShowMongoRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DashboardController.class)
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EnrichedShowMongoRepository repository;

    @Test
    void listsAllShowsAndSplitsOutNeedsReview() throws Exception {
        EnrichedShowDocument exact = document("Dark", "EXACT");
        EnrichedShowDocument fuzzy = document("Dark Matter", "FUZZY");
        EnrichedShowDocument unmatched = document("Unmatched Show", null);

        when(repository.findAllByOrderByTitleAsc()).thenReturn(List.of(exact, fuzzy, unmatched));
        when(repository.findByMatchConfidenceNotInOrMatchConfidenceIsNull(List.of("EXACT", "MANUAL_OVERRIDE")))
                .thenReturn(List.of(fuzzy, unmatched));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("totalCount", 3))
                .andExpect(model().attribute("shows", List.of(exact, fuzzy, unmatched)))
                .andExpect(model().attribute("needsReview", List.of(fuzzy, unmatched)));
    }

    @Test
    void handlesEmptyLibrary() throws Exception {
        when(repository.findAllByOrderByTitleAsc()).thenReturn(List.of());
        when(repository.findByMatchConfidenceNotInOrMatchConfidenceIsNull(List.of("EXACT", "MANUAL_OVERRIDE")))
                .thenReturn(List.of());

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("totalCount", 0));
    }

    private static EnrichedShowDocument document(String title, String matchConfidence) {
        return new EnrichedShowDocument(
                title.toLowerCase().replace(" ", "-"),
                title,
                List.of(),
                matchConfidence,
                "{}",
                Instant.now());
    }
}

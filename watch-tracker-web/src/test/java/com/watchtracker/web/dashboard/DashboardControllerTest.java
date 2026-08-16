package com.watchtracker.web.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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

    @MockBean
    private ShowViewFactory showViewFactory;

    @Test
    void listsShowsWithFilterAll() throws Exception {
        EnrichedShowDocument doc = document("Dark", "EXACT");
        ShowView view = view("id-1", "Dark", "EXACT", false);
        when(repository.findAllByOrderByTitleAsc()).thenReturn(List.of(doc));
        when(showViewFactory.fromDocuments(anyList())).thenReturn(List.of(view));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attribute("totalCount", 1))
                .andExpect(model().attribute("filter", "all"));
    }

    @Test
    void filterReview() throws Exception {
        ShowView ok = view("1", "Dark", "EXACT", false);
        ShowView bad = view("2", "X", null, true);
        when(repository.findAllByOrderByTitleAsc()).thenReturn(List.of());
        when(showViewFactory.fromDocuments(anyList())).thenReturn(List.of(ok, bad));

        mockMvc.perform(get("/").param("filter", "review"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("filter", "review"))
                .andExpect(model().attribute("needsReviewCount", 1L));
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

    private static ShowView view(String id, String title, String confidence, boolean needsReview) {
        return new ShowView(
                id,
                title,
                List.of(),
                confidence,
                "SERIES",
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                "—",
                null,
                null,
                null,
                1,
                null,
                null,
                null,
                null);
    }
}

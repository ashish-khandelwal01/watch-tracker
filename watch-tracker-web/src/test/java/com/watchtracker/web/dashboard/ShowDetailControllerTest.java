package com.watchtracker.web.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.watchtracker.web.persistence.EnrichedShowDocument;
import com.watchtracker.web.persistence.EnrichedShowMongoRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ShowDetailController.class)
class ShowDetailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EnrichedShowMongoRepository repository;

    @MockBean
    private ShowViewFactory showViewFactory;

    @Test
    void detailOk() throws Exception {
        EnrichedShowDocument doc = new EnrichedShowDocument(
                "abc", "Dark", List.of(), "EXACT", "{}", Instant.now());
        ShowView view = new ShowView(
                "abc",
                "Dark",
                List.of(),
                "EXACT",
                "SERIES",
                "https://image.tmdb.org/t/p/w185/x.jpg",
                "https://image.tmdb.org/t/p/w500/x.jpg",
                "Overview text",
                List.of("Drama"),
                "2017-12-01",
                "TMDB",
                123L,
                "S1 E1",
                1,
                1,
                "IN_PROGRESS",
                10,
                null,
                null,
                1,
                1);
        when(repository.findById("abc")).thenReturn(Optional.of(doc));
        when(showViewFactory.fromDocument(any())).thenReturn(view);

        mockMvc.perform(get("/shows/abc"))
                .andExpect(status().isOk())
                .andExpect(view().name("show-detail"))
                .andExpect(model().attribute("show", view));
    }

    @Test
    void detailNotFound() throws Exception {
        when(repository.findById("missing")).thenReturn(Optional.empty());
        mockMvc.perform(get("/shows/missing")).andExpect(status().isNotFound());
    }
}

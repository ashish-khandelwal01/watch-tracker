package com.watchtracker.web.importer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ImportController.class)
class ImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ImportJobRunner jobRunner;

    @MockBean
    private ImportJobStore jobStore;

    @Test
    void showsImportForm() throws Exception {
        mockMvc.perform(get("/import"))
                .andExpect(status().isOk())
                .andExpect(view().name("import"))
                .andExpect(model().attribute("withMetadata", true));
    }

    @Test
    void rejectsBlankCsvPath() throws Exception {
        mockMvc.perform(post("/import").param("csvPath", "  "))
                .andExpect(status().isOk())
                .andExpect(view().name("import"))
                .andExpect(model().attributeExists("error"));

        verify(jobStore, never()).tryStart();
    }

    @Test
    void startsJobAndRedirectsToStatusPage() throws Exception {
        when(jobStore.tryStart()).thenReturn(Optional.of("job-123"));

        mockMvc.perform(post("/import").param("csvPath", "/tmp/history.csv"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/import/status/job-123"));

        verify(jobRunner).runImport(anyString(), any(), anyBoolean());
    }

    @Test
    void refusesSecondImportWhileOneIsRunning() throws Exception {
        when(jobStore.tryStart()).thenReturn(Optional.empty());

        mockMvc.perform(post("/import").param("csvPath", "/tmp/history.csv"))
                .andExpect(status().isOk())
                .andExpect(view().name("import"))
                .andExpect(model().attributeExists("error"));

        verify(jobRunner, never()).runImport(anyString(), any(), anyBoolean());
    }

    @Test
    void statusPageReportsUnknownJobAfterRestart() throws Exception {
        when(jobStore.find("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/import/status/missing"))
                .andExpect(status().isOk())
                .andExpect(view().name("import-status"))
                .andExpect(model().attribute("notFound", true));
    }

    @Test
    void statusPageShowsRunningJob() throws Exception {
        when(jobStore.find("job-123")).thenReturn(Optional.of(ImportJob.running("job-123")));

        mockMvc.perform(get("/import/status/job-123"))
                .andExpect(status().isOk())
                .andExpect(view().name("import-status"))
                .andExpect(model().attributeExists("job"));
    }
}

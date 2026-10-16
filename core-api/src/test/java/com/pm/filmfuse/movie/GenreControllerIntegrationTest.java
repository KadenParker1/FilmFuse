package com.pm.filmfuse.movie;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GenreControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsAllNineteenGenresSortedByName() throws Exception {
        mockMvc.perform(get("/api/v1/genres").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(19)))
                .andExpect(jsonPath("$[0].id").value(28))
                .andExpect(jsonPath("$[0].name").value("Action"))
                .andExpect(jsonPath("$[1].name").value("Adventure"))
                .andExpect(jsonPath("$[14].name").value("Science Fiction"))
                .andExpect(jsonPath("$[15].name").value("Thriller"))
                .andExpect(jsonPath("$[16].name").value("TV Movie"))
                .andExpect(jsonPath("$[18].id").value(37))
                .andExpect(jsonPath("$[18].name").value("Western"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/genres"))
                .andExpect(status().isUnauthorized());
    }
}

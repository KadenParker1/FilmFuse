package com.pm.filmfuse.common.http;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The 64 KB request body cap (filmfuse.http.max-body-size) through the real filter chain. */
class RequestBodyLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static String profileWithBio(int length) {
        return "{\"bio\":\"" + "x".repeat(length) + "\"}";
    }

    @Test
    void aBodyOverTheCapIs413() throws Exception {
        mockMvc.perform(patch("/api/v1/me").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON).content(profileWithBio(70_000)))
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.message").value("The request body is too large."));
    }

    @Test
    void aBodyJustUnderTheCapReachesValidation() throws Exception {
        mockMvc.perform(patch("/api/v1/me").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON).content(profileWithBio(65_000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.bio").exists());
    }

    @Test
    void anOversizedBodyIsRefusedBeforeAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/reports").contentType(MediaType.APPLICATION_JSON).content(profileWithBio(70_000)))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void tenThousandElementArraysAreRefused() throws Exception {
        String batch = IntStream.rangeClosed(1, 10_000)
                .mapToObj(i -> "{\"movieId\":" + i + ",\"score\":5}")
                .collect(Collectors.joining(",", "[", "]"));
        mockMvc.perform(post("/api/v1/me/ratings/batch").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isContentTooLarge());
        String genres = IntStream.rangeClosed(1, 10_000)
                .mapToObj(i -> "{\"genreId\":28,\"score\":5}")
                .collect(Collectors.joining(",", "[", "]"));
        mockMvc.perform(put("/api/v1/me/preferences/genres").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON).content(genres))
                .andExpect(status().isContentTooLarge());
    }

    @Test
    void the413CarriesCorsHeaders() throws Exception {
        mockMvc.perform(patch("/api/v1/me").with(someUser()).header("Origin", "http://localhost:8081")
                        .contentType(MediaType.APPLICATION_JSON).content(profileWithBio(70_000)))
                .andExpect(status().isContentTooLarge())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    }
}

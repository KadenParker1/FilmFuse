package com.pm.filmfuse.user;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET / PUT /me/settings/comfort. */
class ComfortSettingsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void defaultsAreNoLimitsAndNoUnratedContent() throws Exception {
        mockMvc.perform(get("/api/v1/me/settings/comfort").with(someUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxCertification").value(nullValue()))
                .andExpect(jsonPath("$.contentLimits", aMapWithSize(0)))
                .andExpect(jsonPath("$.allowUnrated").value(false));
    }

    @Test
    void putReplacesTheSettingsAndGetReturnsThem() throws Exception {
        UUID id = UUID.randomUUID();
        RequestPostProcessor me = com.pm.filmfuse.support.JwtTestSupport.supabaseUser(id);
        String body = """
                {"maxCertification": "PG_13",
                 "contentLimits": {"VIOLENCE": "MILD", "SEXUAL": "NONE", "LANGUAGE": "STRONG", "DRUGS": null},
                 "allowUnrated": true}
                """;
        mockMvc.perform(put("/api/v1/me/settings/comfort").with(me).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxCertification").value("PG_13"))
                // STRONG (= no limit) and null limits are dropped.
                .andExpect(jsonPath("$.contentLimits", aMapWithSize(2)))
                .andExpect(jsonPath("$.contentLimits.VIOLENCE").value("MILD"))
                .andExpect(jsonPath("$.contentLimits.SEXUAL").value("NONE"))
                .andExpect(jsonPath("$.allowUnrated").value(true));

        mockMvc.perform(get("/api/v1/me/settings/comfort").with(me))
                .andExpect(jsonPath("$.maxCertification").value("PG_13"))
                .andExpect(jsonPath("$.contentLimits.VIOLENCE").value("MILD"))
                .andExpect(jsonPath("$.allowUnrated").value(true));

        // Stored in the ML contract's vocabulary.
        assertThat(jdbc.queryForObject("select max_certification from app.user_settings where user_id = ?", String.class, id))
                .isEqualTo("PG-13");
        assertThat(jdbc.queryForObject("select content_limits::text from app.user_settings where user_id = ?", String.class, id))
                .contains("\"violence\": \"mild\"").contains("\"sexual\": \"none\"").doesNotContain("language");

        // A full replacement: omitted fields go back to the defaults.
        mockMvc.perform(put("/api/v1/me/settings/comfort").with(me).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxCertification").value(nullValue()))
                .andExpect(jsonPath("$.contentLimits", aMapWithSize(0)))
                .andExpect(jsonPath("$.allowUnrated").value(false));
    }

    @Test
    void everyCertificationRoundTrips() throws Exception {
        RequestPostProcessor me = someUser();
        for (String certification : new String[] {"G", "PG", "PG_13", "R", "NC_17"}) {
            mockMvc.perform(put("/api/v1/me/settings/comfort").with(me).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"maxCertification\": \"" + certification + "\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/me/settings/comfort").with(me))
                    .andExpect(jsonPath("$.maxCertification").value(certification));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"maxCertification\": \"PG-13\"}",
            "{\"maxCertification\": \"X\"}",
            "{\"contentLimits\": {\"GORE\": \"MILD\"}}",
            "{\"contentLimits\": {\"VIOLENCE\": \"EXTREME\"}}",
            "{\"contentLimits\": {\"violence\": \"mild\"}}",
            "{\"allowUnrated\": \"maybe\"}",
            "not json"
    })
    void unknownValuesAre400(String body) throws Exception {
        mockMvc.perform(put("/api/v1/me/settings/comfort").with(someUser()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The request body could not be read as JSON."));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me/settings/comfort")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/me/settings/comfort").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void settingsGoWithTheAccount() throws Exception {
        UUID id = UUID.randomUUID();
        RequestPostProcessor me = com.pm.filmfuse.support.JwtTestSupport.supabaseUser(id);
        mockMvc.perform(put("/api/v1/me/settings/comfort").with(me).contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxCertification\": \"G\"}")).andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me").with(me))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from app.user_settings where user_id = ?", Integer.class, id))
                .isZero();
    }
}

package com.pm.filmfuse.common.validation;

import com.pm.filmfuse.rating.AbstractPhase5IntegrationTest;
import com.pm.filmfuse.support.JwtTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SECURITY.md #19: every free-text field other people see refuses control characters, bidi
 * overrides / isolates and edge zero-width characters with a 400 and a field error. Bodies use
 * JSON {@code \\uXXXX} escapes, so the characters reach the server exactly.
 */
class SafeTextIntegrationTest extends AbstractPhase5IntegrationTest {

    /** JSON string contents (escaped) that must be refused in every field. */
    @ParameterizedTest
    @ValueSource(strings = {"evil\\u202Etxt", "a\\u2066b", "a\\u0007b", "a\\u0085b", "\\u200BAda", "Ada\\uFEFF",
            "tab\\there"})
    void refusedEverywhere(String bad) throws Exception {
        TestUser me = user();
        expectFieldError(patchMe(me, "{\"displayName\": \"" + bad + "\"}"), "displayName");
        expectFieldError(patchMe(me, "{\"bio\": \"" + bad + "\"}"), "bio");
        expectFieldError(postJson(me, "/api/v1/groups", "{\"name\": \"" + bad + "\"}"), "name");
        expectFieldError(postJson(me, "/api/v1/groups", "{\"name\": \"G\", \"emoji\": \"" + bad + "\"}"), "emoji");

        UUID groupId = activeGroup(me);
        expectFieldError(mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(me.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"" + bad + "\"}")), "name");

        int movie = newMovie();
        expectFieldError(rate(me, movie, "{\"score\": 7, \"body\": \"" + bad + "\"}"), "body");
        String ratingId = review(me, newMovie(), "PUBLIC");
        expectFieldError(comment(me, ratingId, bad), "body");
        expectFieldError(report(user(), "{\"targetType\": \"RATING\", \"targetId\": \"%s\", \"reason\": \"SPAM\", \"details\": \"%s\"}"
                .formatted(ratingId, bad)), "details");
    }

    @Test
    void lineBreaksAreAllowedInBodiesButNotInNames() throws Exception {
        TestUser me = user();
        expectFieldError(patchMe(me, "{\"displayName\": \"Two\\nlines\"}"), "displayName");
        expectFieldError(postJson(me, "/api/v1/groups", "{\"name\": \"Two\\nlines\"}"), "name");

        patchMe(me, "{\"bio\": \"Line one\\r\\nLine two\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Line one\nLine two"));
        rate(me, newMovie(), "{\"score\": 7, \"body\": \"Great.\\r\\n\\nWould watch again.\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body").value("Great.\n\nWould watch again."));
        String ratingId = review(me, newMovie(), "PUBLIC");
        comment(me, ratingId, "First\\nSecond").andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("First\nSecond"));
        report(user(), "{\"targetType\": \"RATING\", \"targetId\": \"%s\", \"reason\": \"SPAM\", \"details\": \"a\\nb\"}"
                .formatted(ratingId)).andExpect(status().is2xxSuccessful());
        // A lone carriage return is still a control character.
        expectFieldError(comment(me, ratingId, "First\\rSecond"), "body");
    }

    @Test
    void ordinaryUnicodeIsAccepted() throws Exception {
        TestUser me = user();
        patchMe(me, "{\"displayName\": \"Zoë مرحبا 名前\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Zoë مرحبا 名前"));
        postJson(me, "/api/v1/groups", "{\"name\": \"Family night\", \"emoji\": \"👨\\u200D👩\\u200D👧\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.emoji").value("👨‍👩‍👧"));
    }

    @Test
    void anUnsafeNameFromSignInMetadataIsNotUsedAsTheDisplayName() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUserWithName(id, "Ada‮ecalevol")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value(nullValue()));
        assertThat(jdbc.queryForObject("select display_name from app.profiles where id = ?", String.class, id)).isNull();
    }

    private ResultActions patchMe(TestUser user, String json) throws Exception {
        return mockMvc.perform(patch("/api/v1/me").with(user.auth()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static void expectFieldError(ResultActions result, String field) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors." + field).value(SafeText.MESSAGE));
    }
}

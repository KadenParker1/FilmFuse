package com.pm.filmfuse.user;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.JwtTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** profiles.email follows the JWT email claim and never leaves the server. */
class ProfileEmailSyncIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void provisioningStoresTheTokenEmail() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").doesNotExist());
        assertThat(storedEmail(id)).isEqualTo(JwtTestSupport.emailFor(id));
    }

    @Test
    void aChangedClaimIsSyncedOnTheNextRequest() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(id))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me").with(withEmail(id, "  new-address@example.com "))).andExpect(status().isOk());
        assertThat(storedEmail(id)).isEqualTo("new-address@example.com");

        // Any authenticated endpoint resolving the caller syncs it, e.g. a username check.
        mockMvc.perform(get("/api/v1/usernames/whatever/available").with(withEmail(id, "third@example.com")))
                .andExpect(status().isOk());
        assertThat(storedEmail(id)).isEqualTo("third@example.com");
    }

    @Test
    void aTokenWithoutAnEmailKeepsTheStoredOne() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(id))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").with(withEmail(id, null))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me").with(withEmail(id, "   "))).andExpect(status().isOk());
        assertThat(storedEmail(id)).isEqualTo(JwtTestSupport.emailFor(id));
    }

    @Test
    void emailSyncDoesNotClobberAProfileUpdate() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(id))).andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/me").with(withEmail(id, "patched@example.com"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"bio\": \"Still here.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Still here."))
                .andExpect(jsonPath("$.email").doesNotExist());
        assertThat(storedEmail(id)).isEqualTo("patched@example.com");
        assertThat(jdbc.queryForObject("select bio from app.profiles where id = ?", String.class, id)).isEqualTo("Still here.");
    }

    private static RequestPostProcessor withEmail(UUID id, String email) {
        return jwt().jwt(builder -> {
            builder.subject(id.toString()).claim("role", "authenticated");
            if (email != null) {
                builder.claim("email", email);
            }
        }).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private String storedEmail(UUID id) {
        return jdbc.queryForObject("select email from app.profiles where id = ?", String.class, id);
    }
}

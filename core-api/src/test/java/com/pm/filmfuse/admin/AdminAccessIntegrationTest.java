package com.pm.filmfuse.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pm.filmfuse.support.AbstractAdminIntegrationTest;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Who reaches {@code /api/v1/admin/**}: every endpoint answers 401 without a token, 404 to a signed-in
 * non-admin (indistinguishable from an unknown path) and works for an admin. The role comes from
 * {@code profiles.role} on every request, so a grant or revoke applies at once, and no API writes it.
 */
class AdminAccessIntegrationTest extends AbstractAdminIntegrationTest {

    private static final String SOME_ID = "6f1c2b9e-0000-4000-8000-000000000000";

    /** Every admin endpoint, with a request an admin may legitimately send. */
    static Stream<Arguments> adminEndpoints() {
        return Stream.of(
                endpoint("GET /admin/metrics/catalog", () -> get("/api/v1/admin/metrics/catalog")),
                endpoint("GET /admin/metrics/usage", () -> get("/api/v1/admin/metrics/usage")),
                endpoint("GET /admin/metrics/health", () -> get("/api/v1/admin/metrics/health")),
                endpoint("GET /admin/settings", () -> get("/api/v1/admin/settings")),
                endpoint("PUT /admin/settings/{key}", () -> put("/api/v1/admin/settings/catalog.quality.min-votes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\": 7}")),
                endpoint("DELETE /admin/settings/{key}", () -> delete("/api/v1/admin/settings/catalog.quality.min-votes")),
                endpoint("GET /admin/reports", () -> get("/api/v1/admin/reports")),
                endpoint("POST /admin/reports/{id}/resolve", () -> post("/api/v1/admin/reports/" + SOME_ID + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"resolution\": \"DISMISSED\"}")),
                endpoint("GET /admin/audit-log", () -> get("/api/v1/admin/audit-log")),
                endpoint("GET /admin/jobs", () -> get("/api/v1/admin/jobs")),
                endpoint("POST /admin/jobs/{name}/run", () -> post("/api/v1/admin/jobs/closeness/run")),
                endpoint("POST /admin/jobs/{name}/pause", () -> post("/api/v1/admin/jobs/catalog-top-up/pause")),
                endpoint("POST /admin/jobs/{name}/resume", () -> post("/api/v1/admin/jobs/catalog-top-up/resume")));
    }

    private static Arguments endpoint(String name, Supplier<MockHttpServletRequestBuilder> request) {
        return Arguments.of(name, request);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    void unauthenticatedRequestsAre401(String name, Supplier<MockHttpServletRequestBuilder> request) throws Exception {
        mockMvc.perform(request.get()).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    void signedInNonAdminsGetTheSame404AsAnUnknownPath(String name, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        UUID user = newUser();
        MockHttpServletResponse admin = mockMvc.perform(request.get().with(as(user)))
                .andExpect(status().isNotFound()).andReturn().getResponse();
        MockHttpServletResponse unknown = mockMvc.perform(get("/api/v1/no-such-thing").with(as(user)))
                .andExpect(status().isNotFound()).andReturn().getResponse();
        assertThat(admin.getContentAsString()).isEqualTo(unknown.getContentAsString());
        // Both go through sendError(404), so the container renders the same error page (server.error.include-message
        // is off, so the unknown path's internal message never reaches the client).
        assertThat(admin.getContentType()).isEqualTo(unknown.getContentType());
        // Nothing was written on their behalf.
        assertThat(jdbc.queryForObject("select count(*) from app.admin_audit_log where admin_id = ?", Long.class, user))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from app.admin_settings where updated_by = ?", Long.class, user))
                .isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adminEndpoints")
    void adminsAreServed(String name, Supplier<MockHttpServletRequestBuilder> request) throws Exception {
        var result = mockMvc.perform(request.get().with(as(newAdmin())));
        if (name.startsWith("POST /admin/reports")) {
            // It names a report that does not exist: the service's 404 with a message, past the guard.
            result.andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Report not found."));
        } else if (name.startsWith("POST /admin/jobs/{name}/run")) {
            // closeness has no Run now: the service's 400, past the guard (real runs: AdminJobsIntegrationTest).
            result.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("This job can't be run or paused from here."));
        } else {
            result.andExpect(status().isOk());
        }
    }

    @Test
    void unknownAdminPathsAre404ForEveryoneSignedIn() throws Exception {
        mockMvc.perform(get("/api/v1/admin/nope").with(as(newUser()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/nope").with(as(newAdmin()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin").with(as(newUser()))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/admin/nope")).andExpect(status().isUnauthorized());
    }

    @Test
    void aBrandNewUnprovisionedCallerIsNotAnAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/admin/settings").with(as(UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test
    void meExposesIsAdmin() throws Exception {
        mockMvc.perform(get("/api/v1/me").with(as(newUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(false));
        mockMvc.perform(get("/api/v1/me").with(as(newAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(true));
        // First sight of a user provisions a plain user.
        mockMvc.perform(get("/api/v1/me").with(as(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(false));
    }

    @Test
    void grantingAndRevokingApplyOnTheNextRequest() throws Exception {
        UUID id = newUser();
        mockMvc.perform(get("/api/v1/admin/settings").with(as(id))).andExpect(status().isNotFound());
        jdbc.update("update app.profiles set role = 'admin' where id = ?", id);
        mockMvc.perform(get("/api/v1/admin/settings").with(as(id))).andExpect(status().isOk());
        jdbc.update("update app.profiles set role = 'user' where id = ?", id);
        mockMvc.perform(get("/api/v1/admin/settings").with(as(id))).andExpect(status().isNotFound());
    }

    @Test
    void noApiChangesARoleAndProfileSavesKeepIt() throws Exception {
        UUID user = newUser();
        mockMvc.perform(patch("/api/v1/me").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"admin\", \"isAdmin\": true, \"bio\": \"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(false));
        assertThat(jdbc.queryForObject("select role from app.profiles where id = ?", String.class, user)).isEqualTo("user");

        UUID admin = newAdmin();
        mockMvc.perform(patch("/api/v1/me").with(as(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\": \"still in charge\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(true));
        assertThat(jdbc.queryForObject("select role from app.profiles where id = ?", String.class, admin))
                .isEqualTo("admin");
    }

    @Test
    void theRoleColumnOnlyAcceptsUserOrAdmin() {
        UUID user = newUser();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("update app.profiles set role = 'superuser' where id = ?", user))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select role from app.profiles where id = ?", String.class, user)).isEqualTo("user");
    }

    @Test
    void suspendedAdminsAreStillRefused() throws Exception {
        UUID admin = newAdmin();
        jdbc.update("update app.profiles set status = 'SUSPENDED' where id = ?", admin);
        mockMvc.perform(get("/api/v1/admin/settings").with(as(admin))).andExpect(status().isUnauthorized());
    }
}

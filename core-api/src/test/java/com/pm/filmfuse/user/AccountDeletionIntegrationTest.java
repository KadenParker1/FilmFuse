package com.pm.filmfuse.user;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.common.exception.UpstreamUnavailableException;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.JwtTestSupport;
import com.pm.filmfuse.user.gateway.AuthAdminGateway;
import com.pm.filmfuse.user.gateway.StorageGateway;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** DELETE /me against the mock gateways, spied so failures can be injected. */
class AccountDeletionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @MockitoSpyBean
    private AuthAdminGateway authAdminGateway;

    @MockitoSpyBean
    private StorageGateway storageGateway;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Lets a test send a real {@code Authorization: Bearer} header, so the request goes through
     * SupabaseJwtAuthenticationConverter (MockMvc's jwt() post-processor skips it).
     */
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void deletedAccountsTokenIsRejectedAndNeverReprovisions() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = bearerTokenFor(userId);

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertThat(isTombstoned(userId)).isTrue();

        // The same, still-unexpired token: 401 before any controller runs, and no new profile.
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("account_deleted")));
        mockMvc.perform(get("/api/v1/movies/popular").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        assertThat(profileRepository.findById(userId)).isEmpty();
    }

    @Test
    void provisioningRefusesATombstonedIdEvenPastTheConverter() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into app.deleted_accounts (id) values (?)", userId);

        // jwt() skips the converter; the provisioning guard must still refuse.
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("This account has been deleted."));
        assertThat(profileRepository.findById(userId)).isEmpty();
    }

    @Test
    void liveAccountsTokenStillPassesTheConverter() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + bearerTokenFor(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
        assertThat(isTombstoned(userId)).isFalse();
    }

    @Test
    void deleteMeRemovesTheRowThePhotoAndTheAuthUser() throws Exception {
        UUID userId = UUID.randomUUID();
        String path = withPhoto(userId);

        mockMvc.perform(delete("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(profileRepository.findById(userId)).isEmpty();
        assertThat(isTombstoned(userId)).isTrue();
        var order = inOrder(storageGateway, authAdminGateway);
        order.verify(storageGateway).delete("profile-photos", path);
        order.verify(authAdminGateway).deleteUser(userId);
    }

    @Test
    void deleteMeWithoutAPhotoSkipsStorage() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(userId))).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isNoContent());

        assertThat(profileRepository.findById(userId)).isEmpty();
        verify(storageGateway, never()).delete(anyString(), anyString());
        verify(authAdminGateway).deleteUser(userId);
    }

    @Test
    void authAdminFailureStillSucceedsWithTheRowGone() throws Exception {
        UUID userId = UUID.randomUUID();
        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(userId))).andExpect(status().isOk());
        doThrow(new UpstreamUnavailableException("Account service is temporarily unavailable."))
                .when(authAdminGateway).deleteUser(userId);

        mockMvc.perform(delete("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isNoContent());

        assertThat(profileRepository.findById(userId)).isEmpty();
        assertThat(isTombstoned(userId)).as("tombstone is written before the admin call").isTrue();
        verify(authAdminGateway).deleteUser(userId);
    }

    @Test
    void storageFailureDoesNotBlockDeletion() throws Exception {
        UUID userId = UUID.randomUUID();
        withPhoto(userId);
        doThrow(new UpstreamUnavailableException("Photo storage is temporarily unavailable."))
                .when(storageGateway).delete(eq("profile-photos"), anyString());

        mockMvc.perform(delete("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isNoContent());

        assertThat(profileRepository.findById(userId)).isEmpty();
        verify(authAdminGateway).deleteUser(userId);
    }

    @Test
    void storageFailureOnPhotoReplaceIsBestEffort() throws Exception {
        UUID userId = UUID.randomUUID();
        withPhoto(userId);
        doThrow(new UpstreamUnavailableException("Photo storage is temporarily unavailable."))
                .when(storageGateway).delete(any(), any());
        String next = issuePath(userId);

        mockMvc.perform(post("/api/v1/me/profile-photo").with(JwtTestSupport.supabaseUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": \"" + next + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(storageGateway.publicUrl("profile-photos", next)));

        mockMvc.perform(delete("/api/v1/me/profile-photo").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isOk());
        assertThat(profileRepository.findById(userId).orElseThrow().getProfileImageUrl()).isNull();
    }

    @Test
    void uploadUrlStorageOutageIsA502() throws Exception {
        doThrow(new UpstreamUnavailableException("Photo storage is temporarily unavailable."))
                .when(storageGateway).createSignedUploadUrl(anyString(), anyString(), org.mockito.ArgumentMatchers.anyInt());

        mockMvc.perform(post("/api/v1/me/profile-photo/upload-url").with(someUser()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Photo storage is temporarily unavailable."));
    }

    @Test
    void deleteMeRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/v1/me")).andExpect(status().isUnauthorized());
        verify(authAdminGateway, never()).deleteUser(any());
    }

    private String withPhoto(UUID userId) throws Exception {
        String path = issuePath(userId);
        mockMvc.perform(post("/api/v1/me/profile-photo").with(JwtTestSupport.supabaseUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": \"" + path + "\"}"))
                .andExpect(status().isOk());
        return path;
    }

    private String issuePath(UUID userId) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/me/profile-photo/upload-url")
                        .with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.path");
    }

    private String bearerTokenFor(UUID userId) {
        String token = "token-" + userId;
        Instant now = Instant.now();
        when(jwtDecoder.decode(token)).thenReturn(Jwt.withTokenValue(token)
                .header("alg", "ES256")
                .subject(userId.toString())
                .claim("role", "authenticated")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .build());
        return token;
    }

    private boolean isTombstoned(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from app.deleted_accounts where id = ?)", Boolean.class, userId));
    }
}

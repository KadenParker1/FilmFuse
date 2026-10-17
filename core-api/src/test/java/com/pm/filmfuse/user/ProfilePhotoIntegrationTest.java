package com.pm.filmfuse.user;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.JwtTestSupport;
import com.pm.filmfuse.user.gateway.MockStorageGateway;
import com.pm.filmfuse.user.gateway.StorageGateway;
import com.pm.filmfuse.user.repository.ProfileRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runs with the MockStorageGateway (no service-role key in tests). */
class ProfilePhotoIntegrationTest extends AbstractIntegrationTest {

    private static final String PUBLIC_PREFIX = "http://127.0.0.1:55321/storage/v1/object/public/profile-photos/";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProfileRepository profileRepository;

    @Autowired
    private StorageGateway storageGateway;

    @Test
    void storageIsTheMockInTests() {
        assertThat(storageGateway).isInstanceOf(MockStorageGateway.class);
    }

    @Test
    void uploadUrlIsSignedForAFreshPathInTheCallersFolder() throws Exception {
        UUID userId = UUID.randomUUID();

        String body = mockMvc.perform(post("/api/v1/me/profile-photo/upload-url").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(7200))
                .andReturn().getResponse().getContentAsString();

        String path = JsonPath.read(body, "$.path");
        String uploadUrl = JsonPath.read(body, "$.uploadUrl");
        assertThat(path).matches("^" + userId + "/[0-9a-f-]{36}\\.jpg$");
        assertThat(uploadUrl).isEqualTo("http://127.0.0.1:55321/storage/v1/object/upload/sign/profile-photos/"
                + path + "?token=mock-upload-token");
        assertThat(mockStorage().calls()).contains(new MockStorageGateway.Call("sign-upload", "profile-photos", path));

        // Every request gets a new object path.
        String second = JsonPath.read(mockMvc.perform(post("/api/v1/me/profile-photo/upload-url")
                        .with(JwtTestSupport.supabaseUser(userId)))
                .andReturn().getResponse().getContentAsString(), "$.path");
        assertThat(second).isNotEqualTo(path);
    }

    @Test
    void confirmSetsThePublicUrl() throws Exception {
        UUID userId = UUID.randomUUID();
        String path = issuePath(userId);

        mockMvc.perform(confirm(userId, path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.profileImageUrl").value(PUBLIC_PREFIX + path));

        mockMvc.perform(get("/api/v1/me").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(jsonPath("$.profileImageUrl").value(PUBLIC_PREFIX + path));
        assertThat(profileRepository.findById(userId).orElseThrow().getProfileImageUrl()).isEqualTo(PUBLIC_PREFIX + path);
    }

    @Test
    void confirmingANewPhotoDeletesThePreviousObject() throws Exception {
        UUID userId = UUID.randomUUID();
        String first = issuePath(userId);
        String second = issuePath(userId);

        mockMvc.perform(confirm(userId, first)).andExpect(status().isOk());
        assertThat(mockStorage().deletedPaths()).doesNotContain(first);

        mockMvc.perform(confirm(userId, second))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(PUBLIC_PREFIX + second));

        assertThat(mockStorage().deletedPaths()).contains(first).doesNotContain(second);
    }

    @Test
    void reconfirmingTheSamePhotoDeletesNothing() throws Exception {
        UUID userId = UUID.randomUUID();
        String path = issuePath(userId);

        mockMvc.perform(confirm(userId, path)).andExpect(status().isOk());
        mockMvc.perform(confirm(userId, path)).andExpect(status().isOk());

        assertThat(mockStorage().deletedPaths()).doesNotContain(path);
    }

    @Test
    void confirmRejectsAnotherUsersPath() throws Exception {
        UUID caller = UUID.randomUUID();
        String foreign = issuePath(UUID.randomUUID());

        mockMvc.perform(confirm(caller, foreign))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That is not a photo path issued to you."));
        assertThat(profileRepository.findById(caller).orElseThrow().getProfileImageUrl()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{id}/abc.jpg",
            "{id}/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.png",
            "{id}/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpeg",
            "{id}/0B1C2D3E-4F50-6172-8394-A5B6C7D8E9F0.jpg",
            "{id}/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpg/x.jpg",
            "{id}/../other/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpg",
            "/{id}/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpg",
            "{id}0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpg",
            "0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpg",
            "{id}/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpg\n",
            "https://evil.example/{id}/0b1c2d3e-4f50-6172-8394-a5b6c7d8e9f0.jpg"
    })
    void confirmRejectsMalformedPaths(String template) throws Exception {
        UUID userId = UUID.randomUUID();
        String path = template.replace("{id}", userId.toString());

        mockMvc.perform(post("/api/v1/me/profile-photo").with(JwtTestSupport.supabaseUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": " + jsonString(path) + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That is not a photo path issued to you."));
    }

    @Test
    void confirmRequiresAPath() throws Exception {
        mockMvc.perform(post("/api/v1/me/profile-photo").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.fieldErrors.path").value("Path is required"));

        mockMvc.perform(post("/api/v1/me/profile-photo").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\": \"" + "a".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.path").exists());

        mockMvc.perform(post("/api/v1/me/profile-photo").with(someUser())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteClearsThePhotoAndDeletesTheObject() throws Exception {
        UUID userId = UUID.randomUUID();
        String path = issuePath(userId);
        mockMvc.perform(confirm(userId, path)).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/me/profile-photo").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()))
                .andExpect(jsonPath("$.profileImageUrl").value(nullValue()));

        assertThat(mockStorage().deletedPaths()).contains(path);
        assertThat(profileRepository.findById(userId).orElseThrow().getProfileImageUrl()).isNull();
    }

    @Test
    void deleteWithoutAPhotoIsANoOp() throws Exception {
        UUID userId = UUID.randomUUID();
        int before = mockStorage().deletedPaths().size();

        mockMvc.perform(delete("/api/v1/me/profile-photo").with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(nullValue()));

        assertThat(mockStorage().deletedPaths().stream().filter(p -> p.startsWith(userId.toString()))).isEmpty();
        assertThat(mockStorage().deletedPaths().size()).isGreaterThanOrEqualTo(before);
    }

    @Test
    void patchMeIgnoresProfileImageUrl() throws Exception {
        UUID userId = UUID.randomUUID();
        String path = issuePath(userId);
        mockMvc.perform(confirm(userId, path)).andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/me").with(JwtTestSupport.supabaseUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bio\": \"Still me.\", \"profileImageUrl\": \"https://evil.example/x.jpg\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bio").value("Still me."))
                .andExpect(jsonPath("$.profileImageUrl").value(PUBLIC_PREFIX + path));

        // Clearing via PATCH is ignored too.
        mockMvc.perform(patch("/api/v1/me").with(JwtTestSupport.supabaseUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileImageUrl\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(PUBLIC_PREFIX + path));
    }

    @Test
    void everyPhotoEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/me/profile-photo/upload-url")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/me/profile-photo")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/me/profile-photo")).andExpect(status().isUnauthorized());
    }

    private String issuePath(UUID userId) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/me/profile-photo/upload-url")
                        .with(JwtTestSupport.supabaseUser(userId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.path");
    }

    private static org.springframework.test.web.servlet.RequestBuilder confirm(UUID userId, String path) {
        RequestPostProcessor user = JwtTestSupport.supabaseUser(userId);
        return post("/api/v1/me/profile-photo").with(user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"path\": " + jsonString(path) + "}");
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private MockStorageGateway mockStorage() {
        return (MockStorageGateway) storageGateway;
    }
}

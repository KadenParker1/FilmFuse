package com.pm.filmfuse.group;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every Phase 3 route needs a signed-in user. */
class GroupSecurityIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyGroupSessionAndComfortRouteRequiresAuthentication() throws Exception {
        UUID id = UUID.randomUUID();
        Object[][] routes = {
                {HttpMethod.POST, "/api/v1/groups"},
                {HttpMethod.GET, "/api/v1/groups"},
                {HttpMethod.GET, "/api/v1/groups/" + id},
                {HttpMethod.PATCH, "/api/v1/groups/" + id},
                {HttpMethod.DELETE, "/api/v1/groups/" + id},
                {HttpMethod.POST, "/api/v1/groups/" + id + "/members"},
                {HttpMethod.DELETE, "/api/v1/groups/" + id + "/members/" + id},
                {HttpMethod.POST, "/api/v1/groups/" + id + "/invites/accept"},
                {HttpMethod.POST, "/api/v1/groups/" + id + "/invites/decline"},
                {HttpMethod.POST, "/api/v1/sessions"},
                {HttpMethod.GET, "/api/v1/sessions"},
                {HttpMethod.GET, "/api/v1/sessions/" + id},
                {HttpMethod.PUT, "/api/v1/sessions/" + id + "/votes/1"},
                {HttpMethod.DELETE, "/api/v1/sessions/" + id + "/votes/1"},
                {HttpMethod.POST, "/api/v1/sessions/" + id + "/refresh"},
                {HttpMethod.POST, "/api/v1/sessions/" + id + "/pick"},
                {HttpMethod.GET, "/api/v1/me/settings/comfort"},
                {HttpMethod.PUT, "/api/v1/me/settings/comfort"},
        };
        for (Object[] route : routes) {
            mockMvc.perform(request((HttpMethod) route[0], (String) route[1])
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }
}

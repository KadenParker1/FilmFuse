package com.pm.filmfuse.social;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Every social endpoint needs a token. */
class SocialSecurityIntegrationTest extends AbstractSocialIntegrationTest {

    @Test
    void everyEndpointIs401WithoutAToken() throws Exception {
        String id = UUID.randomUUID().toString();
        Object[][] endpoints = {
                {HttpMethod.GET, "/api/v1/users/search?q=ab"},
                {HttpMethod.GET, "/api/v1/users/" + id},
                {HttpMethod.GET, "/api/v1/friends"},
                {HttpMethod.PATCH, "/api/v1/friends/" + id},
                {HttpMethod.DELETE, "/api/v1/friends/" + id},
                {HttpMethod.GET, "/api/v1/friends/requests?direction=INCOMING"},
                {HttpMethod.POST, "/api/v1/friends/requests"},
                {HttpMethod.POST, "/api/v1/friends/requests/" + id + "/accept"},
                {HttpMethod.POST, "/api/v1/friends/requests/" + id + "/decline"},
                {HttpMethod.DELETE, "/api/v1/friends/requests/" + id},
                {HttpMethod.GET, "/api/v1/friends/suggestions"},
                {HttpMethod.GET, "/api/v1/blocks"},
                {HttpMethod.POST, "/api/v1/blocks/" + id},
                {HttpMethod.DELETE, "/api/v1/blocks/" + id},
                {HttpMethod.POST, "/api/v1/invites"},
                {HttpMethod.POST, "/api/v1/invites/sometoken/redeem"},
        };
        for (Object[] endpoint : endpoints) {
            mockMvc.perform(request((HttpMethod) endpoint[0], (String) endpoint[1]))
                    .andExpect(status().isUnauthorized());
        }
    }
}

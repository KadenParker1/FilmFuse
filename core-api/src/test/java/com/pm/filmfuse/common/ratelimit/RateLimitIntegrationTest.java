package com.pm.filmfuse.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pm.filmfuse.support.AbstractIntegrationTest;
import com.pm.filmfuse.support.JwtTestSupport;
import com.pm.filmfuse.support.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The rate limiter through the real security chain, with small limits: IP 40/min, user 15/min,
 * every category 3/hour. Each test uses its own user and client address, so tests never share a
 * bucket (the context, and so the limiter, is shared by every test here).
 */
@TestPropertySource(properties = {
        "filmfuse.rate-limit.enabled=true",
        "filmfuse.rate-limit.limits.ip.capacity=40",
        "filmfuse.rate-limit.limits.user.capacity=15",
        "filmfuse.rate-limit.limits.friend-requests.capacity=3",
        "filmfuse.rate-limit.limits.invites.capacity=3",
        "filmfuse.rate-limit.limits.reports.capacity=3",
        "filmfuse.rate-limit.limits.comments.capacity=3",
        "filmfuse.rate-limit.limits.sessions.capacity=3",
        "filmfuse.rate-limit.limits.photo-upload.capacity=3",
})
class RateLimitIntegrationTest extends AbstractIntegrationTest {

    private static final MutableClock CLOCK = new MutableClock();
    private static final AtomicInteger NEXT_ADDRESS = new AtomicInteger(1);

    @TestBean(name = "clock")
    private Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    private MockMvc mockMvc;

    /** A client address no other test uses. */
    private static RequestPostProcessor freshAddress() {
        int n = NEXT_ADDRESS.getAndIncrement();
        String address = "10.77." + (n / 250) + "." + (n % 250 + 1);
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static RequestPostProcessor user(UUID id) {
        return JwtTestSupport.supabaseUser(id);
    }

    static Stream<Arguments> categories() {
        String someId = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("friend-requests", (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/v1/friends/requests")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetUserId\":\"" + someId + "\"}")),
                Arguments.of("invites", (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/v1/invites")),
                Arguments.of("invites (group invites)", (Supplier<MockHttpServletRequestBuilder>) () ->
                        post("/api/v1/groups/" + someId + "/members").contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userIds\":[\"" + someId + "\"]}")),
                Arguments.of("reports", (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                Arguments.of("comments", (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/v1/ratings/" + someId + "/comments")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"hi\"}")),
                Arguments.of("sessions", (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/v1/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                Arguments.of("photo-upload", (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/v1/me/profile-photo/upload-url")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("categories")
    void eachCategoryAllowsItsLimitThenAnswers429WithRetryAfter(String name, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        UUID me = UUID.randomUUID();
        RequestPostProcessor from = freshAddress();
        for (int i = 0; i < 3; i++) {
            int code = mockMvc.perform(request.get().with(user(me)).with(from)).andReturn().getResponse().getStatus();
            assertThat(code).as("%s request %d is handled (whatever its outcome)", name, i + 1).isNotEqualTo(429);
        }
        MvcResult refused = mockMvc.perform(request.get().with(user(me)).with(from))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Too many requests.")))
                .andReturn();
        // 3 per hour = one token every 20 minutes.
        long retryAfter = Long.parseLong(refused.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1190L, 1200L);

        // Other endpoints are only limited by the general per-user bucket.
        mockMvc.perform(get("/api/v1/me").with(user(me)).with(from)).andExpect(status().isOk());
        // Another user is unaffected.
        assertThat(mockMvc.perform(request.get().with(user(UUID.randomUUID())).with(from)).andReturn().getResponse()
                .getStatus()).isNotEqualTo(429);
    }

    @Test
    void aCategoryRefillsAfterRetryAfter() throws Exception {
        UUID me = UUID.randomUUID();
        RequestPostProcessor from = freshAddress();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/invites").with(user(me)).with(from)).andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/invites").with(user(me)).with(from)).andExpect(status().isTooManyRequests());

        CLOCK.advance(Duration.ofMinutes(20));
        mockMvc.perform(post("/api/v1/invites").with(user(me)).with(from)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/invites").with(user(me)).with(from)).andExpect(status().isTooManyRequests());
    }

    @Test
    void theGeneralUserLimitCoversEveryEndpointAndFollowsTheUserAcrossAddresses() throws Exception {
        UUID me = UUID.randomUUID();
        RequestPostProcessor first = freshAddress();
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/api/v1/me").with(user(me)).with(first)).andExpect(status().isOk());
        }
        MvcResult refused = mockMvc.perform(get("/api/v1/genres").with(user(me)).with(freshAddress()))
                .andExpect(status().isTooManyRequests())
                .andReturn();
        // 15 per minute = one token every 4 seconds.
        assertThat(Long.parseLong(refused.getResponse().getHeader("Retry-After"))).isBetween(1L, 4L);
        mockMvc.perform(get("/api/v1/me").with(user(UUID.randomUUID())).with(first)).andExpect(status().isOk());
    }

    @Test
    void theAddressLimitIsSharedByEveryoneBehindIt() throws Exception {
        RequestPostProcessor shared = freshAddress();
        for (int i = 0; i < 40; i++) {
            // A fresh user each time: only the address bucket fills up.
            mockMvc.perform(get("/api/v1/me").with(user(UUID.randomUUID())).with(shared)).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/me").with(user(UUID.randomUUID())).with(shared))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        mockMvc.perform(get("/api/v1/me").with(user(UUID.randomUUID())).with(freshAddress())).andExpect(status().isOk());
    }

    @Test
    void unauthenticatedRequestsAreLimitedByAddress() throws Exception {
        RequestPostProcessor from = freshAddress();
        for (int i = 0; i < 40; i++) {
            mockMvc.perform(get("/api/v1/me").with(from)).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/me").with(from)).andExpect(status().isTooManyRequests());
    }

    @Test
    void healthChecksAndPreflightsAreNeverLimited() throws Exception {
        RequestPostProcessor from = freshAddress();
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(get("/actuator/health").with(from)).andExpect(status().isOk());
        }
        for (int i = 0; i < 60; i++) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options("/api/v1/me")
                            .header("Origin", "http://localhost:8081")
                            .header("Access-Control-Request-Method", "GET")
                            .with(from))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void a429CarriesCorsHeadersSoTheWebAppCanReadIt() throws Exception {
        RequestPostProcessor from = freshAddress();
        for (int i = 0; i < 40; i++) {
            mockMvc.perform(get("/api/v1/me").with(from).header("Origin", "http://localhost:8081"));
        }
        mockMvc.perform(get("/api/v1/me").with(from).header("Origin", "http://localhost:8081"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    }
}

package com.pm.filmfuse.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.pm.filmfuse.admin.security.AdminAccess;
import com.pm.filmfuse.admin.service.MetricsCache;
import com.pm.filmfuse.admin.settings.AdminSettingDefinition;
import com.pm.filmfuse.common.exception.FieldValidationException;
import com.pm.filmfuse.reco.ml.MlHealthProbe;
import com.pm.filmfuse.reco.ml.MlProperties;
import com.pm.filmfuse.support.MutableClock;
import com.pm.filmfuse.user.model.Profile;
import com.pm.filmfuse.user.repository.DeletedAccountRepository;
import com.pm.filmfuse.user.repository.ProfileRepository;
import com.pm.filmfuse.user.security.SupabaseJwtAuthenticationConverter;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** The admin portal's pure pieces: setting validation, the metrics cache, the ML probe, the guard, the role. */
class AdminUnitTest {

    @Nested
    class SettingDefinitions {

        private final AdminSettingDefinition votes = AdminSettingDefinition.integer("k", "G", "L", "D", "votes", 0, 100,
                () -> 5);
        private final AdminSettingDefinition flag = AdminSettingDefinition.bool("f", "G", "L", "D", () -> true);

        @Test
        void integersAcceptWholeNumbersWithinInclusiveBounds() {
            assertThat(votes.validate(0)).isEqualTo(0L);
            assertThat(votes.validate(100L)).isEqualTo(100L);
            assertThat(votes.validate((short) 7)).isEqualTo(7L);
        }

        @Test
        void integersRefuseEverythingElse() {
            for (Object bad : new Object[] {-1, 101, 5.0, 5.5f, "5", true, BigInteger.TEN.pow(30), null}) {
                assertThatThrownBy(() -> votes.validate(bad)).as(String.valueOf(bad))
                        .isInstanceOf(FieldValidationException.class)
                        .satisfies(ex -> assertThat(((FieldValidationException) ex).fieldErrors()).containsKey("value"));
            }
        }

        @Test
        void booleansAcceptOnlyBooleans() {
            assertThat(flag.validate(false)).isEqualTo(false);
            for (Object bad : new Object[] {1, "true", 0.0, null}) {
                assertThatThrownBy(() -> flag.validate(bad)).isInstanceOf(FieldValidationException.class);
            }
        }

        @Test
        void effectiveValuesFallBackAndClamp() {
            assertThat(votes.effective(null)).isEqualTo(5L);
            assertThat(votes.effective(500)).isEqualTo(100L);
            assertThat(votes.effective(-3)).isEqualTo(0L);
            assertThat(votes.effective(2.5)).isEqualTo(5L);
            assertThat(votes.effective("x")).isEqualTo(5L);
            assertThat(flag.effective(false)).isEqualTo(false);
            assertThat(flag.effective("false")).isEqualTo(true);
        }

        @Test
        void anIntegerNeedsSaneBounds() {
            assertThatThrownBy(() -> AdminSettingDefinition.integer("k", "G", "L", "D", null, 10, 1, () -> 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Cache {

        @Test
        void reusesAValueUntilTheTtlPasses() {
            MutableClock clock = new MutableClock();
            MetricsCache cache = new MetricsCache(Duration.ofSeconds(30), clock);
            AtomicInteger computed = new AtomicInteger();
            assertThat(cache.get("a", computed::incrementAndGet)).isEqualTo(1);
            assertThat(cache.get("a", computed::incrementAndGet)).isEqualTo(1);
            assertThat(cache.get("b", computed::incrementAndGet)).isEqualTo(2);
            clock.advance(Duration.ofSeconds(31));
            assertThat(cache.get("a", computed::incrementAndGet)).isEqualTo(3);
        }

        @Test
        void aZeroTtlNeverCaches() {
            MetricsCache cache = new MetricsCache(Duration.ZERO, new MutableClock());
            AtomicInteger computed = new AtomicInteger();
            cache.get("a", computed::incrementAndGet);
            cache.get("a", computed::incrementAndGet);
            assertThat(computed).hasValue(2);
        }
    }

    @Nested
    class MlHealth {

        private MockRestServiceServer server;

        private MlHealthProbe probe(String baseUrl) {
            RestClient.Builder builder = RestClient.builder();
            server = MockRestServiceServer.bindTo(builder).build();
            return new MlHealthProbe(builder, new MlProperties(baseUrl, "k".repeat(32)), new MutableClock());
        }

        @Test
        void notConfiguredMeansNotCalled() {
            MlHealthProbe.MlHealth health = probe("").check();
            assertThat(health.configured()).isFalse();
            assertThat(health.reachable()).isFalse();
            assertThat(health.latencyMs()).isNull();
            server.verify();
        }

        @Test
        void readsStatusAndAModelVersionWhenExposed() {
            MlHealthProbe probe = probe("http://ml.internal");
            server.expect(requestTo("http://ml.internal/health")).andExpect(method(HttpMethod.GET))
                    .andExpect(header("X-Internal-Key", "k".repeat(32)))
                    .andRespond(withSuccess("{\"status\":\"ok\",\"model_version\":\"placeholder-1\"}",
                            MediaType.APPLICATION_JSON));
            MlHealthProbe.MlHealth health = probe.check();
            assertThat(health.reachable()).isTrue();
            assertThat(health.status()).isEqualTo("ok");
            assertThat(health.modelVersion()).isEqualTo("placeholder-1");
            assertThat(health.latencyMs()).isNotNull();
        }

        @Test
        void withoutAModelVersionItIsNull() {
            MlHealthProbe probe = probe("http://ml.internal");
            server.expect(requestTo("http://ml.internal/health"))
                    .andRespond(withSuccess("{\"status\":\"ok\"}", MediaType.APPLICATION_JSON));
            assertThat(probe.check().modelVersion()).isNull();
        }

        @Test
        void anErrorIsUnreachable() {
            MlHealthProbe probe = probe("http://ml.internal");
            server.expect(requestTo("http://ml.internal/health")).andRespond(withServerError());
            MlHealthProbe.MlHealth health = probe.check();
            assertThat(health.configured()).isTrue();
            assertThat(health.reachable()).isFalse();
        }
    }

    @Nested
    class Guard {

        @Test
        void adminPathsAre404OthersKeepTheDefault403() throws Exception {
            MockHttpServletRequest admin = new MockHttpServletRequest("GET", "/api/v1/admin/settings");
            MockHttpServletResponse adminResponse = new MockHttpServletResponse();
            AdminAccess.accessDeniedHandler().handle(admin, adminResponse, new AccessDeniedException("no"));
            assertThat(adminResponse.getStatus()).isEqualTo(404);

            MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/v1/groups");
            MockHttpServletResponse otherResponse = new MockHttpServletResponse();
            AdminAccess.accessDeniedHandler().handle(other, otherResponse, new AccessDeniedException("no"));
            assertThat(otherResponse.getStatus()).isEqualTo(403);
        }

        @Test
        void recognisesTheAdminPrefixOnly() {
            assertThat(AdminAccess.isAdminPath("/api/v1/admin")).isTrue();
            assertThat(AdminAccess.isAdminPath("/api/v1/admin/x")).isTrue();
            assertThat(AdminAccess.isAdminPath("/api/v1/administrator")).isFalse();
            assertThat(AdminAccess.isAdminPath("/api/v1/me")).isFalse();
        }
    }

    @Nested
    class Role {

        private final ProfileRepository profiles = Mockito.mock(ProfileRepository.class);
        private final DeletedAccountRepository deleted = Mockito.mock(DeletedAccountRepository.class);
        private final SupabaseJwtAuthenticationConverter converter = new SupabaseJwtAuthenticationConverter(profiles,
                deleted);

        private Jwt jwt(UUID id) {
            return Jwt.withTokenValue("t").header("alg", "ES256").subject(id.toString())
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        }

        private Profile profile(UUID id, String role) {
            Profile profile = new Profile(id);
            ReflectionTestUtils.setField(profile, "role", role);
            return profile;
        }

        @Test
        void theAdminAuthorityComesFromTheRowOnly() {
            UUID admin = UUID.randomUUID();
            UUID user = UUID.randomUUID();
            UUID unknown = UUID.randomUUID();
            Mockito.when(profiles.findById(admin)).thenReturn(Optional.of(profile(admin, "admin")));
            Mockito.when(profiles.findById(user)).thenReturn(Optional.of(profile(user, "user")));
            Mockito.when(profiles.findById(unknown)).thenReturn(Optional.empty());

            assertThat(converter.convert(jwt(admin)).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
            assertThat(converter.convert(jwt(user)).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
            assertThat(converter.convert(jwt(unknown)).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
        }

        @Test
        void aTokenClaimingAdminIsIgnored() {
            UUID user = UUID.randomUUID();
            Mockito.when(profiles.findById(user)).thenReturn(Optional.of(profile(user, "user")));
            Jwt claiming = Jwt.withTokenValue("t").header("alg", "ES256").subject(user.toString())
                    .claim("role", "admin").claim("app_metadata", java.util.Map.of("role", "admin"))
                    .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
            assertThat(converter.convert(claiming).getAuthorities()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_USER");
        }

        @Test
        void isAdminReadsTheRole() {
            assertThat(profile(UUID.randomUUID(), "admin").isAdmin()).isTrue();
            assertThat(profile(UUID.randomUUID(), "user").isAdmin()).isFalse();
            assertThat(new Profile(UUID.randomUUID()).isAdmin()).isFalse();
        }
    }
}

package com.pm.filmfuse.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RateLimitRuleTest {

    @ParameterizedTest
    @CsvSource({
            "POST, /api/v1/friends/requests, FRIEND_REQUESTS",
            "POST, /api/v1/invites, INVITES",
            "POST, /api/v1/invites/abc123/redeem, INVITES",
            "POST, /api/v1/groups, INVITES",
            "POST, /api/v1/groups/6f1c2b9e-0000-4000-8000-000000000000/members, INVITES",
            "POST, /api/v1/reports, REPORTS",
            "POST, /api/v1/ratings/6f1c2b9e-0000-4000-8000-000000000000/comments, COMMENTS",
            "POST, /api/v1/sessions, SESSIONS",
            "POST, /api/v1/me/profile-photo/upload-url, PHOTO_UPLOAD",
            "PUT, /api/v1/admin/settings/catalog.requests-per-second, ADMIN_WRITES",
            "DELETE, /api/v1/admin/settings/catalog.requests-per-second, ADMIN_WRITES",
            "POST, /api/v1/admin/jobs/catalog-top-up/run, ADMIN_WRITES",
            "PATCH, /api/v1/admin/anything, ADMIN_WRITES",
    })
    void abuseProneWritesHaveACategory(String method, String path, RateLimitRule expected) {
        assertThat(RateLimitRule.categoryOf(method, path)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "GET, /api/v1/friends/requests",
            "GET, /api/v1/ratings/6f1c2b9e-0000-4000-8000-000000000000/comments",
            "DELETE, /api/v1/friends/requests/6f1c2b9e-0000-4000-8000-000000000000",
            "POST, /api/v1/friends/requests/6f1c2b9e-0000-4000-8000-000000000000/accept",
            "POST, /api/v1/sessions/6f1c2b9e-0000-4000-8000-000000000000/pick",
            "POST, /api/v1/me/profile-photo",
            "PUT, /api/v1/ratings/550",
            "GET, /api/v1/admin/settings",
            "GET, /api/v1/admin/metrics/catalog",
    })
    void everythingElseOnlyCountsTowardsTheGeneralLimits(String method, String path) {
        assertThat(RateLimitRule.categoryOf(method, path)).isEqualTo(Optional.empty());
    }

    @org.junit.jupiter.api.Test
    void onlyTheAdminCategoryIsAdminOnly() {
        for (RateLimitRule rule : RateLimitRule.values()) {
            assertThat(rule.adminOnly()).as(rule.name()).isEqualTo(rule == RateLimitRule.ADMIN_WRITES);
        }
    }
}

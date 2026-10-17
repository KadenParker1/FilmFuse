package com.pm.filmfuse.user;

import com.pm.filmfuse.rating.AbstractPhase5IntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** GET/PUT /me/settings/notifications (contract section E). */
class NotificationSettingsIntegrationTest extends AbstractPhase5IntegrationTest {

    private static final String ALL_OFF = """
            {"friendRequests": false, "groupInvites": false, "sessionStarted": false, "comments": false,
             "weeklyDigest": true}""";

    @Test
    void defaultsAreEverythingOnExceptTheDigest() throws Exception {
        TestUser user = user();
        notifications(user).andExpect(status().isOk())
                .andExpect(jsonPath("$.friendRequests").value(true))
                .andExpect(jsonPath("$.groupInvites").value(true))
                .andExpect(jsonPath("$.sessionStarted").value(true))
                .andExpect(jsonPath("$.comments").value(true))
                .andExpect(jsonPath("$.weeklyDigest").value(false));
        assertThat(rows("select count(*) from app.notification_settings where user_id = ?", user.id())).isZero();
    }

    @Test
    void putReplacesAllAndPersists() throws Exception {
        TestUser user = user();
        putNotifications(user, ALL_OFF).andExpect(status().isOk())
                .andExpect(jsonPath("$.friendRequests").value(false))
                .andExpect(jsonPath("$.groupInvites").value(false))
                .andExpect(jsonPath("$.sessionStarted").value(false))
                .andExpect(jsonPath("$.comments").value(false))
                .andExpect(jsonPath("$.weeklyDigest").value(true));
        notifications(user).andExpect(jsonPath("$.comments").value(false)).andExpect(jsonPath("$.weeklyDigest").value(true));

        switchOff(user, "groupInvites");
        notifications(user).andExpect(jsonPath("$.friendRequests").value(true))
                .andExpect(jsonPath("$.groupInvites").value(false))
                .andExpect(jsonPath("$.weeklyDigest").value(false));
        assertThat(rows("select count(*) from app.notification_settings where user_id = ?", user.id())).isEqualTo(1);
        // Someone else's settings are untouched.
        notifications(user()).andExpect(jsonPath("$.groupInvites").value(true));
    }

    @Test
    void everyFieldIsRequired() throws Exception {
        TestUser user = user();
        for (String field : new String[] {"friendRequests", "groupInvites", "sessionStarted", "comments", "weeklyDigest"}) {
            String missing = ALL_OFF.replaceAll("\"" + field + "\": (true|false),?", "").replace(",\n}", "}")
                    .replaceAll(",\\s*}", "}");
            putNotifications(user, missing).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors." + field).value("Required"));
            putNotifications(user, ALL_OFF.replaceAll("\"" + field + "\": (true|false)", "\"" + field + "\": null"))
                    .andExpect(status().isBadRequest());
        }
        putNotifications(user, "{\"friendRequests\": \"maybe\"}").andExpect(status().isBadRequest());
        putNotifications(user, "nope").andExpect(status().isBadRequest());
        notifications(user).andExpect(jsonPath("$.friendRequests").value(true));
    }
}

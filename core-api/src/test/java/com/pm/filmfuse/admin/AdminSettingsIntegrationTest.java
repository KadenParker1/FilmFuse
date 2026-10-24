package com.pm.filmfuse.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pm.filmfuse.admin.service.AdminSettingsService;
import com.pm.filmfuse.admin.settings.AdminSettingsRegistry;
import com.pm.filmfuse.support.AbstractAdminIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Runtime settings: the registry, type and bounds validation, unknown keys, reset, and the audit trail. */
class AdminSettingsIntegrationTest extends AbstractAdminIntegrationTest {

    @Autowired
    private AdminSettingsService settings;

    private UUID admin;

    @BeforeEach
    void cleanSettings() {
        jdbc.update("delete from app.admin_settings");
        admin = newAdmin();
    }

    private ResultActions putSetting(String key, String valueJson) throws Exception {
        return mockMvc.perform(put("/api/v1/admin/settings/{key}", key).with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": " + valueJson + "}"));
    }

    private List<Map<String, Object>> auditRows(String target) {
        return jdbc.queryForList("""
                select action, target, admin_id, old_value::text as old_value, new_value::text as new_value
                from app.admin_audit_log where target = ? and admin_id = ? order by created_at
                """, target, admin);
    }

    @Test
    void listsEveryRegisteredKeyWithItsDefaultAndBounds() throws Exception {
        mockMvc.perform(get("/api/v1/admin/settings").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", hasItem("catalog.top-up.size")))
                .andExpect(jsonPath("$[*].key", hasItem("catalog.refresh.main-tier-size")))
                .andExpect(jsonPath("$[*].key", hasItem("catalog.quality.min-votes")))
                .andExpect(jsonPath("$[*].key", hasItem("catalog.quality.require-poster")))
                .andExpect(jsonPath("$[*].key", hasItem("catalog.quality.require-overview")))
                .andExpect(jsonPath("$[*].key", hasItem("catalog.max-database-mb")))
                .andExpect(jsonPath("$[*].key", hasItem("catalog.requests-per-second")))
                .andExpect(jsonPath("$[?(@.key == 'catalog.quality.min-votes')].value").value(5))
                .andExpect(jsonPath("$[?(@.key == 'catalog.quality.min-votes')].defaultValue").value(5))
                .andExpect(jsonPath("$[?(@.key == 'catalog.quality.min-votes')].min").value(0))
                .andExpect(jsonPath("$[?(@.key == 'catalog.quality.min-votes')].max").value(100))
                .andExpect(jsonPath("$[?(@.key == 'catalog.quality.min-votes')].overridden").value(false))
                .andExpect(jsonPath("$[?(@.key == 'catalog.quality.min-votes')].type").value("INTEGER"))
                .andExpect(jsonPath("$[?(@.key == 'catalog.quality.require-poster')].type").value("BOOLEAN"))
                .andExpect(jsonPath("$[?(@.key == 'catalog.quality.require-poster')].value").value(true))
                .andExpect(jsonPath("$[?(@.key == 'catalog.max-database-mb')].max").value(480))
                .andExpect(jsonPath("$[?(@.key == 'catalog.requests-per-second')].min").value(1))
                .andExpect(jsonPath("$[?(@.key == 'catalog.requests-per-second')].max").value(40));
    }

    @Test
    void anUpdateIsStoredReturnedAppliedAndAudited() throws Exception {
        putSetting("catalog.quality.min-votes", "12")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("catalog.quality.min-votes"))
                .andExpect(jsonPath("$.value").value(12))
                .andExpect(jsonPath("$.defaultValue").value(5))
                .andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.updatedBy.id").value(admin.toString()))
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
        assertThat(settings.intValue(AdminSettingsRegistry.QUALITY_MIN_VOTES)).isEqualTo(12);

        putSetting("catalog.quality.min-votes", "20").andExpect(status().isOk());
        assertThat(settings.intValue(AdminSettingsRegistry.QUALITY_MIN_VOTES)).isEqualTo(20);

        List<Map<String, Object>> audit = auditRows("catalog.quality.min-votes");
        assertThat(audit).hasSize(2);
        assertThat(audit.get(0)).containsEntry("action", "SETTING_UPDATE").containsEntry("new_value", "12");
        assertThat(audit.get(0).get("old_value")).isNull();
        assertThat(audit.get(1)).containsEntry("old_value", "12").containsEntry("new_value", "20");
    }

    @Test
    void booleansAreStoredAsBooleans() throws Exception {
        putSetting("catalog.quality.require-poster", "false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(false));
        assertThat(settings.boolValue(AdminSettingsRegistry.QUALITY_REQUIRE_POSTER)).isFalse();
        assertThat(auditRows("catalog.quality.require-poster").getFirst()).containsEntry("new_value", "false");
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
            "catalog.top-up.size | 99 | Must be between 100 and 5000 movies.",
            "catalog.top-up.size | 5001 | Must be between 100 and 5000 movies.",
            "catalog.refresh.main-tier-size | 999 | Must be between 1000 and 150000 movies.",
            "catalog.refresh.main-tier-size | 150001 | Must be between 1000 and 150000 movies.",
            "catalog.quality.min-votes | -1 | Must be between 0 and 100 votes.",
            "catalog.quality.min-votes | 101 | Must be between 0 and 100 votes.",
            "catalog.max-database-mb | 49 | Must be between 50 and 480 MB.",
            "catalog.max-database-mb | 481 | Must be between 50 and 480 MB.",
            "catalog.max-database-mb | 99999999999999999999999 | Must be between 50 and 480 MB.",
            "catalog.requests-per-second | 0 | Must be between 1 and 40 requests/s.",
            "catalog.requests-per-second | 41 | Must be between 1 and 40 requests/s.",
            "catalog.requests-per-second | 2.5 | Must be a whole number.",
            "catalog.quality.min-votes | 5.0 | Must be a whole number.",
            "catalog.quality.min-votes | '\"5\"' | Must be a whole number.",
            "catalog.quality.min-votes | true | Must be a whole number.",
            "catalog.quality.min-votes | null | A value is required. To go back to the default, reset the setting.",
            "catalog.quality.require-poster | 1 | Must be true or false.",
            "catalog.quality.require-poster | '\"true\"' | Must be true or false.",
    })
    void outOfBoundsOrWrongTypeIs400WithAFieldErrorAndStoresNothing(String key, String value, String message)
            throws Exception {
        putSetting(key, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.value").value(message));
        assertThat(jdbc.queryForObject("select count(*) from app.admin_settings", Long.class)).isZero();
        assertThat(auditRows(key)).isEmpty();
    }

    @Test
    void boundsAreInclusive() throws Exception {
        putSetting("catalog.top-up.size", "100").andExpect(status().isOk());
        putSetting("catalog.top-up.size", "5000").andExpect(status().isOk());
        putSetting("catalog.max-database-mb", "480").andExpect(status().isOk());
        putSetting("catalog.requests-per-second", "40").andExpect(status().isOk());
        putSetting("catalog.quality.min-votes", "0").andExpect(status().isOk());
    }

    @Test
    void unknownKeysAre400AndNeverStored() throws Exception {
        putSetting("catalog.something-else", "1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown setting."));
        mockMvc.perform(delete("/api/v1/admin/settings/{key}", "role").with(as(admin)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from app.admin_settings", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from app.admin_audit_log where admin_id = ?", Long.class,
                admin)).isZero();
    }

    @Test
    void aMissingOrMalformedBodyIs400() throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings/{key}", "catalog.quality.min-votes").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("not json")).andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/admin/settings/{key}", "catalog.quality.min-votes").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.value").exists());
    }

    @Test
    void resetReturnsToTheDefaultAndIsAuditedOnlyWhenSomethingChanged() throws Exception {
        putSetting("catalog.requests-per-second", "35").andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/admin/settings/{key}", "catalog.requests-per-second").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(20))
                .andExpect(jsonPath("$.overridden").value(false));
        assertThat(settings.intValue(AdminSettingsRegistry.REQUESTS_PER_SECOND)).isEqualTo(20);
        // Idempotent: a second reset changes nothing and writes no audit row.
        mockMvc.perform(delete("/api/v1/admin/settings/{key}", "catalog.requests-per-second").with(as(admin)))
                .andExpect(status().isOk());

        List<Map<String, Object>> audit = auditRows("catalog.requests-per-second");
        assertThat(audit).extracting(row -> row.get("action")).containsExactly("SETTING_UPDATE", "SETTING_RESET");
        assertThat(audit.get(1)).containsEntry("old_value", "35");
        assertThat(audit.get(1).get("new_value")).isNull();
    }

    @Test
    void aStoredValueOutsideTodaysBoundsIsClampedAndAWrongTypeFallsBackToTheDefault() throws Exception {
        jdbc.update("insert into app.admin_settings (key, value) values ('catalog.max-database-mb', '9000'::jsonb)");
        jdbc.update("insert into app.admin_settings (key, value) values ('catalog.quality.require-poster', '\"yes\"'::jsonb)");
        assertThat(settings.intValue(AdminSettingsRegistry.MAX_DATABASE_MB)).isEqualTo(480);
        assertThat(settings.boolValue(AdminSettingsRegistry.QUALITY_REQUIRE_POSTER)).isTrue();
        mockMvc.perform(get("/api/v1/admin/settings").with(as(admin)))
                .andExpect(jsonPath("$[?(@.key == 'catalog.max-database-mb')].value").value(480));
    }

    @Test
    void auditRecordingRequiresTheWritesOwnTransaction() {
        // record() is MANDATORY: called outside a transaction it refuses rather than writing a stray row.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> auditLog.record(admin,
                        com.pm.filmfuse.admin.dto.AdminAction.SETTING_UPDATE, "x", null, 1))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Autowired
    private com.pm.filmfuse.admin.service.AdminAuditLog auditLog;
}

package com.pm.filmfuse.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.pm.filmfuse.support.AbstractAdminIntegrationTest;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** {@code GET /admin/audit-log}: newest first, paged, filterable by action, naming the admin. */
class AdminAuditLogIntegrationTest extends AbstractAdminIntegrationTest {

    private UUID admin;

    @BeforeEach
    void cleanLog() {
        jdbc.update("delete from app.admin_audit_log");
        jdbc.update("delete from app.admin_settings");
        admin = newAdmin();
    }

    private void setVotes(int value) throws Exception {
        mockMvc.perform(put("/api/v1/admin/settings/catalog.quality.min-votes").with(as(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\": " + value + "}")).andExpect(status().isOk());
    }

    @Test
    void listsEveryWriteNewestFirstWithTheAdminAndValues() throws Exception {
        setVotes(10);
        setVotes(11);
        mockMvc.perform(delete("/api/v1/admin/settings/catalog.quality.min-votes").with(as(admin)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/admin/audit-log").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].action").value(Matchers.contains("SETTING_RESET", "SETTING_UPDATE",
                        "SETTING_UPDATE")))
                .andExpect(jsonPath("$.items[0].admin.id").value(admin.toString()))
                .andExpect(jsonPath("$.items[0].admin.username").isNotEmpty())
                .andExpect(jsonPath("$.items[0].target").value("catalog.quality.min-votes"))
                .andExpect(jsonPath("$.items[0].oldValue").value(11))
                .andExpect(jsonPath("$.items[0].newValue").doesNotExist())
                .andExpect(jsonPath("$.items[1].oldValue").value(10))
                .andExpect(jsonPath("$.items[1].newValue").value(11))
                .andExpect(jsonPath("$.items[2].oldValue").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void pagesAndFilters() throws Exception {
        for (int i = 0; i < 5; i++) {
            setVotes(20 + i);
        }
        mockMvc.perform(delete("/api/v1/admin/settings/catalog.quality.min-votes").with(as(admin)));

        String page1 = mockMvc.perform(get("/api/v1/admin/audit-log").param("limit", "4").with(as(admin)))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(page1, "$.nextCursor");
        mockMvc.perform(get("/api/v1/admin/audit-log").param("limit", "4").param("cursor", cursor).with(as(admin)))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[1].newValue").value(20))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "SETTING_RESET").with(as(admin)))
                .andExpect(jsonPath("$.items.length()").value(1));
        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "JOB_RUN").with(as(admin)))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void anAdminWhoLeftIsShownAsNull() throws Exception {
        setVotes(30);
        jdbc.update("delete from app.profiles where id = ?", admin);
        UUID other = newAdmin();
        mockMvc.perform(get("/api/v1/admin/audit-log").with(as(other)))
                .andExpect(jsonPath("$.items[0].admin").doesNotExist())
                .andExpect(jsonPath("$.items[0].newValue").value(30));
    }

    @Test
    void badParametersAre400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log").param("limit", "0").with(as(admin))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/audit-log").param("limit", "101").with(as(admin))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/audit-log").param("cursor", "nope").with(as(admin))).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "DROP_TABLE").with(as(admin)))
                .andExpect(status().isBadRequest());
    }
}

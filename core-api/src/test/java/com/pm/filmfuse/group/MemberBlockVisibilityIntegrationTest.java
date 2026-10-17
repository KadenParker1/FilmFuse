package com.pm.filmfuse.group;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SECURITY.md #15: people blocked either way with the viewer are left out of group and session
 * member lists, previews, the session creator and {@code memberScores} keys, while the counts stay
 * accurate and everyone else still sees every member.
 */
class MemberBlockVisibilityIntegrationTest extends AbstractGroupIntegrationTest {

    @Test
    void groupDetailAndListLeaveOutPeopleBlockedEitherWay() throws Exception {
        TestUser owner = user();
        TestUser viewer = user();
        TestUser blocker = user();
        UUID groupId = activeGroup(owner, viewer, blocker);
        block(blocker, viewer);

        // The blocked person no longer sees the blocker...
        getGroup(viewer, groupId).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.invitedCount").value(0))
                .andExpect(jsonPath("$.members[*].user.id",
                        containsInAnyOrder(owner.id().toString(), viewer.id().toString())));
        // ...nor the blocker them (either way).
        getGroup(blocker, groupId).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.members[*].user.id",
                        containsInAnyOrder(owner.id().toString(), blocker.id().toString())));
        // Uninvolved members see everyone.
        getGroup(owner, groupId).andExpect(status().isOk()).andExpect(jsonPath("$.members", hasSize(3)));

        mockMvc.perform(get("/api/v1/groups").with(viewer.auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberCount").value(3))
                .andExpect(jsonPath("$[0].memberPreview[*].id",
                        containsInAnyOrder(owner.id().toString(), viewer.id().toString())));
        mockMvc.perform(get("/api/v1/groups").with(owner.auth())).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].memberPreview", hasSize(3)));
    }

    @Test
    void invitedMembersAreCountedAndHiddenTheSameWay() throws Exception {
        TestUser owner = user();
        TestUser viewer = user();
        TestUser invitee = user();
        UUID groupId = activeGroup(owner, viewer);
        befriend(owner, invitee);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\": [\"" + invitee.id() + "\"]}"))
                .andExpect(status().is2xxSuccessful());
        block(viewer, invitee);

        getGroup(viewer, groupId).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(2))
                .andExpect(jsonPath("$.invitedCount").value(1))
                .andExpect(jsonPath("$.members", hasSize(2)));
    }

    @Test
    void sessionLeavesOutBlockedMembersCreatorAndTheirScoresButKeepsCounts() throws Exception {
        TestUser owner = user();
        TestUser viewer = user();
        TestUser blocker = user();
        UUID groupId = activeGroup(owner, viewer, blocker);
        int genre = uniqueGenre();
        int movieId = movie(genre, "PG", 50);
        UUID sessionId = sessionId(createGroupSession(blocker, groupId, genre));
        vote(blocker, sessionId, movieId, "UP").andExpect(status().isOk());
        block(blocker, viewer);

        getSession(viewer, sessionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.memberCount").value(3))
                .andExpect(jsonPath("$.votedCount").value(1))
                .andExpect(jsonPath("$.createdBy").value(nullValue()))
                .andExpect(jsonPath("$.members[*].user.id",
                        containsInAnyOrder(owner.id().toString(), viewer.id().toString())))
                .andExpect(jsonPath("$.results[0].memberScores").value(not(hasKey(blocker.id().toString()))))
                .andExpect(jsonPath("$.results[0].memberScores", hasKey(owner.id().toString())))
                .andExpect(jsonPath("$.results[0].memberScores", hasKey(viewer.id().toString())))
                // The tally still counts the hidden member's vote.
                .andExpect(jsonPath("$.results[0].votes.up").value(1));

        // Others still see every member and every score: the stored ranking is untouched.
        getSession(owner, sessionId).andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(3)))
                .andExpect(jsonPath("$.createdBy.id").value(blocker.id().toString()))
                .andExpect(jsonPath("$.results[0].memberScores", hasKey(blocker.id().toString())));

        // Summaries in the history keep the full count.
        mockMvc.perform(get("/api/v1/sessions").with(viewer.auth()).param("groupId", groupId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].memberCount").value(3));
    }
}

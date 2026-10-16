package com.pm.filmfuse.group;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The /groups endpoints and every group rule in the Phase 3 contract. */
class GroupIntegrationTest extends AbstractGroupIntegrationTest {

    // ------------------------------------------------------------------------------- create

    @Test
    void createMakesTheCallerOwnerAndInvitesFriends() throws Exception {
        TestUser owner = user();
        TestUser friend = user();
        befriend(owner, friend);

        String body = json(mockMvc.perform(post("/api/v1/groups").with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"  Roommates  \", \"emoji\": \"🍿\", \"color\": \"#F5B942\", "
                                + "\"memberIds\": [\"" + friend.id() + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Roommates"))
                .andExpect(jsonPath("$.emoji").value("🍿"))
                .andExpect(jsonPath("$.color").value("#F5B942"))
                .andExpect(jsonPath("$.ownerId").value(owner.id().toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.memberStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.members", hasSize(2)))
                .andExpect(jsonPath("$.members[0].user.id").value(owner.id().toString()))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.members[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.members[0].joinedAt").isNotEmpty())
                .andExpect(jsonPath("$.members[1].user.id").value(friend.id().toString()))
                .andExpect(jsonPath("$.members[1].user.friendship").value("FRIENDS"))
                .andExpect(jsonPath("$.members[1].role").value("MEMBER"))
                .andExpect(jsonPath("$.members[1].status").value("INVITED"))
                .andExpect(jsonPath("$.members[1].joinedAt").value(nullValue()))
                .andExpect(jsonPath("$.comfortCap.maxCertification").value(nullValue()))
                .andExpect(jsonPath("$.comfortCap.contentLimits").isEmpty())
                .andExpect(jsonPath("$.effectiveFilter.label").value("No limits"))
                .andExpect(jsonPath("$.recentSessions").isEmpty()));
        assertThat(JsonPath.<String>read(body, "$.id")).isNotBlank();
    }

    @Test
    void createWithNoMembersIsAGroupOfOne() throws Exception {
        TestUser owner = user();
        mockMvc.perform(post("/api/v1/groups").with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Solo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members", hasSize(1)))
                .andExpect(jsonPath("$.emoji").value(nullValue()))
                .andExpect(jsonPath("$.color").value(nullValue()));
    }

    @Test
    void createIgnoresTheCallersOwnIdAndDuplicates() throws Exception {
        TestUser owner = user();
        TestUser friend = user();
        befriend(owner, friend);
        mockMvc.perform(post("/api/v1/groups").with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"G\", \"memberIds\": [\"" + owner.id() + "\", \"" + friend.id()
                                + "\", \"" + friend.id() + "\"]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members", hasSize(2)));
    }

    @Test
    void createRejectsNonFriendsStrangersAndBlockedPeople() throws Exception {
        TestUser owner = user();
        TestUser stranger = user();
        TestUser pending = user();
        TestUser blocker = user();
        sendRequest(owner, pending).andExpect(status().isOk());
        befriend(owner, blocker);
        block(blocker, owner);

        for (UUID id : List.of(stranger.id(), pending.id(), blocker.id(), UUID.randomUUID())) {
            mockMvc.perform(post("/api/v1/groups").with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\": \"G\", \"memberIds\": [\"" + id + "\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("You can only add your friends to a group."));
        }
        assertThat(jdbc.queryForObject("select count(*) from app.groups where owner_id = ?", Integer.class, owner.id()))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\": \"\"}",
            "{\"name\": \"   \"}",
            "{}",
            "{\"name\": \"12345678901234567890123456789012345678901\"}",
            "{\"name\": \"G\", \"emoji\": \"123456789\"}",
            "{\"name\": \"G\", \"color\": \"red\"}",
            "{\"name\": \"G\", \"color\": \"#12345\"}",
            "{\"name\": \"G\", \"memberIds\": [null]}"
    })
    void createValidatesTheBody(String body) throws Exception {
        mockMvc.perform(post("/api/v1/groups").with(user().auth()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void createAcceptsFortyCharacterNamesAndEightCharacterEmoji() throws Exception {
        mockMvc.perform(post("/api/v1/groups").with(user().auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"1234567890123456789012345678901234567890\", \"emoji\": \"12345678\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void createRejectsTwentyInvitees() throws Exception {
        TestUser owner = user();
        String ids = java.util.stream.IntStream.range(0, 20).mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(","));
        mockMvc.perform(post("/api/v1/groups").with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"G\", \"memberIds\": [" + ids + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.memberIds").exists());
    }

    // ------------------------------------------------------------------------------- list

    @Test
    void listShowsActiveGroupsByLatestSessionThenInvites() throws Exception {
        TestUser me = user();
        TestUser other = user();
        befriend(me, other);
        UUID quiet = id(createGroup(me, "Quiet"));
        UUID busy = id(createGroup(me, "Busy"));
        UUID invitedTo = id(createGroup(other, "Theirs", me));
        int genre = uniqueGenre();
        movie(genre, "PG", 10);
        sessionId(createGroupSession(me, busy, genre));

        mockMvc.perform(get("/api/v1/groups").with(me.auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].id").value(busy.toString()))
                .andExpect(jsonPath("$[0].lastSessionAt").isNotEmpty())
                .andExpect(jsonPath("$[0].role").value("OWNER"))
                .andExpect(jsonPath("$[0].memberStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[1].id").value(quiet.toString()))
                .andExpect(jsonPath("$[1].lastSessionAt").value(nullValue()))
                .andExpect(jsonPath("$[2].id").value(invitedTo.toString()))
                .andExpect(jsonPath("$[2].role").value("MEMBER"))
                .andExpect(jsonPath("$[2].memberStatus").value("INVITED"))
                .andExpect(jsonPath("$[2].memberCount").value(1))
                .andExpect(jsonPath("$[2].memberPreview[0].id").value(other.id().toString()));
    }

    @Test
    void listCountsActiveMembersAndPreviewsAtMostFive() throws Exception {
        TestUser owner = user();
        List<TestUser> members = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            members.add(user());
        }
        TestUser invitee = user();
        UUID groupId = activeGroup(owner, members.toArray(TestUser[]::new));
        befriend(owner, invitee);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + invitee.id() + "\"]}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/groups").with(owner.auth()))
                .andExpect(jsonPath("$[0].memberCount").value(7))
                .andExpect(jsonPath("$[0].memberPreview", hasSize(5)))
                .andExpect(jsonPath("$[0].memberPreview[0].id").value(owner.id().toString()));
    }

    @Test
    void listIsEmptyForSomeoneWithoutGroups() throws Exception {
        mockMvc.perform(get("/api/v1/groups").with(user().auth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ------------------------------------------------------------------------------- get

    @Test
    void invitedMembersCanViewTheGroup() throws Exception {
        TestUser owner = user();
        TestUser invitee = user();
        befriend(owner, invitee);
        UUID groupId = id(createGroup(owner, "G", invitee));

        getGroup(invitee, groupId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.memberStatus").value("INVITED"));
    }

    @Test
    void nonMembersAndUnknownGroupsAre404() throws Exception {
        TestUser owner = user();
        UUID groupId = id(createGroup(owner, "G"));
        getGroup(user(), groupId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Group not found."));
        getGroup(owner, UUID.randomUUID()).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/groups/not-a-uuid").with(owner.auth())).andExpect(status().isBadRequest());
    }

    @Test
    void effectiveFilterUsesActiveMembersAndTheCapWithoutAttribution() throws Exception {
        TestUser owner = user();
        TestUser strict = user();
        TestUser invitee = user();
        UUID groupId = activeGroup(owner, strict);
        befriend(owner, invitee);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + invitee.id() + "\"]}"))
                .andExpect(status().isOk());
        setComfort(strict, "{\"maxCertification\": \"PG_13\", \"contentLimits\": {\"VIOLENCE\": \"MILD\"}, \"allowUnrated\": true}");
        // An INVITED member's settings do not count yet.
        setComfort(invitee, "{\"maxCertification\": \"G\", \"contentLimits\": {}, \"allowUnrated\": true}");

        String body = json(getGroup(owner, groupId)
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG_13"))
                .andExpect(jsonPath("$.effectiveFilter.contentLimits.VIOLENCE").value("MILD"))
                // The owner never changed settings: unrated content stays excluded.
                .andExpect(jsonPath("$.effectiveFilter.allowUnrated").value(false))
                .andExpect(jsonPath("$.effectiveFilter.label").value("Up to PG-13 · mild violence")));
        assertThat(body).doesNotContain("\"label\":\"" + strict.username());
    }

    // ------------------------------------------------------------------------------- patch

    @Test
    void ownerCanRenameRecolourAndSetACap() throws Exception {
        TestUser owner = user();
        UUID groupId = id(createGroup(owner, "Old"));

        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \" Kids \", \"emoji\": \"🧸\", \"color\": \"#C7354A\", "
                                + "\"comfortCap\": {\"maxCertification\": \"PG\", \"contentLimits\": {\"FRIGHTENING\": \"MILD\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kids"))
                .andExpect(jsonPath("$.emoji").value("🧸"))
                .andExpect(jsonPath("$.color").value("#C7354A"))
                .andExpect(jsonPath("$.comfortCap.maxCertification").value("PG"))
                .andExpect(jsonPath("$.comfortCap.contentLimits.FRIGHTENING").value("MILD"))
                .andExpect(jsonPath("$.effectiveFilter.maxCertification").value("PG"))
                .andExpect(jsonPath("$.effectiveFilter.label").value("Up to PG · mild frightening scenes"));

        // Null = unchanged; empty emoji / color clears them; a cap of nulls removes it.
        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"emoji\": \"\", \"color\": \"\", \"comfortCap\": {\"maxCertification\": null}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kids"))
                .andExpect(jsonPath("$.emoji").value(nullValue()))
                .andExpect(jsonPath("$.color").value(nullValue()))
                .andExpect(jsonPath("$.comfortCap.maxCertification").value(nullValue()))
                .andExpect(jsonPath("$.comfortCap.contentLimits").isEmpty());

        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Kids"));
    }

    @Test
    void patchIsOwnerOnly() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        TestUser invitee = user();
        UUID groupId = activeGroup(owner, member);
        befriend(owner, invitee);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + invitee.id() + "\"]}"));

        for (TestUser notOwner : List.of(member, invitee)) {
            mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(notOwner.auth())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Mine now\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value("Only the group's owner can do that."));
        }
        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(user().auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Mine now\"}"))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"name\": \"\"}", "{\"name\": \"  \"}", "{\"color\": \"blue\"}", "{\"emoji\": \"123456789\"}",
            "{\"comfortCap\": {\"maxCertification\": \"PG13\"}}",
            "{\"comfortCap\": {\"contentLimits\": {\"GORE\": \"MILD\"}}}"})
    void patchValidatesTheBody(String body) throws Exception {
        TestUser owner = user();
        UUID groupId = id(createGroup(owner, "G"));
        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(owner.auth()).contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------------- delete

    @Test
    void ownerDeletesTheGroup() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = activeGroup(owner, member);

        mockMvc.perform(delete("/api/v1/groups/{id}", groupId).with(member.auth()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/groups/{id}", groupId).with(user().auth()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/groups/{id}", groupId).with(owner.auth()))
                .andExpect(status().isNoContent());

        getGroup(owner, groupId).andExpect(status().isNotFound());
        getGroup(member, groupId).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select count(*) from app.group_members where group_id = ?", Integer.class, groupId))
                .isZero();
    }

    @Test
    void deletingAGroupKeepsItsSessions() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = activeGroup(owner, member);
        int genre = uniqueGenre();
        movie(genre, "PG", 1);
        UUID sessionId = sessionId(createGroupSession(owner, groupId, genre));

        mockMvc.perform(delete("/api/v1/groups/{id}", groupId).with(owner.auth())).andExpect(status().isNoContent());

        getSession(member, sessionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()))
                .andExpect(jsonPath("$.groupName").value(nullValue()));
    }

    // ------------------------------------------------------------------------------- members

    @Test
    void activeMembersInviteTheirOwnFriends() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        TestUser membersFriend = user();
        UUID groupId = activeGroup(owner, member);
        befriend(member, membersFriend);

        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(member.auth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userIds\": [\"" + membersFriend.id() + "\", \"" + owner.id() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(3)))
                .andExpect(jsonPath("$.members[2].user.id").value(membersFriend.id().toString()))
                .andExpect(jsonPath("$.members[2].status").value("INVITED"));

        // Not the owner's friend, so the owner cannot invite them... but they are already a member: ignored.
        befriend(owner, membersFriend);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + membersFriend.id() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(3)));
    }

    @Test
    void invitingSomeoneWhoIsNotYourFriendIs400() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        TestUser ownersFriend = user();
        UUID groupId = activeGroup(owner, member);
        befriend(owner, ownersFriend);

        // The owner's friend is not the member's friend.
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(member.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + ownersFriend.id() + "\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You can only add your friends to a group."));
    }

    @Test
    void invitedMembersCannotInviteAndOutsidersGet404() throws Exception {
        TestUser owner = user();
        TestUser invitee = user();
        TestUser inviteesFriend = user();
        befriend(owner, invitee);
        befriend(invitee, inviteesFriend);
        UUID groupId = id(createGroup(owner, "G", invitee));
        String body = "{\"userIds\": [\"" + inviteesFriend.id() + "\"]}";

        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(invitee.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Accept the group invite first."));
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(inviteesFriend.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"userIds\": []}", "{\"userIds\": [null]}"})
    void addMembersValidatesTheBody(String body) throws Exception {
        TestUser owner = user();
        UUID groupId = id(createGroup(owner, "G"));
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void groupsHoldAtMostTwentyPeopleIncludingInvites() throws Exception {
        TestUser owner = user();
        List<TestUser> friends = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            TestUser friend = user();
            befriend(owner, friend);
            friends.add(friend);
        }
        UUID groupId = id(createGroup(owner, "Big", friends.subList(0, 19).toArray(TestUser[]::new)));

        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + friends.get(19).id() + "\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A group can have at most 20 members."));

        // A decline frees a place.
        mockMvc.perform(post("/api/v1/groups/{id}/invites/decline", groupId).with(friends.get(0).auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + friends.get(19).id() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members", hasSize(20)));
    }

    @Test
    void ownerRemovesMembersAndTheyLoseAccess() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        TestUser invitee = user();
        UUID groupId = activeGroup(owner, member);
        befriend(owner, invitee);
        mockMvc.perform(post("/api/v1/groups/{id}/members", groupId).with(owner.auth())
                .contentType(MediaType.APPLICATION_JSON).content("{\"userIds\": [\"" + invitee.id() + "\"]}"));

        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, member.id()).with(owner.auth()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, invitee.id()).with(owner.auth()))
                .andExpect(status().isNoContent());

        getGroup(member, groupId).andExpect(status().isNotFound());
        getGroup(invitee, groupId).andExpect(status().isNotFound());
        getGroup(owner, groupId).andExpect(jsonPath("$.members", hasSize(1)));
    }

    @Test
    void onlyTheOwnerRemovesOthers() throws Exception {
        TestUser owner = user();
        TestUser a = user();
        TestUser b = user();
        UUID groupId = activeGroup(owner, a, b);

        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, b.id()).with(a.auth()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, owner.id()).with(a.auth()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, b.id()).with(user().auth()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, UUID.randomUUID()).with(owner.auth()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("That person is not in this group."));
    }

    @Test
    void membersLeave() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = activeGroup(owner, member);

        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, member.id()).with(member.auth()))
                .andExpect(status().isNoContent());

        getGroup(member, groupId).andExpect(status().isNotFound());
        getGroup(owner, groupId).andExpect(jsonPath("$.members", hasSize(1)));
    }

    @Test
    void ownerLeavingPassesOwnershipToTheEarliestJoinedActiveMember() throws Exception {
        TestUser owner = user();
        TestUser first = user();
        TestUser second = user();
        TestUser invitee = user();
        for (TestUser u : List.of(first, second, invitee)) {
            befriend(owner, u);
        }
        UUID groupId = id(createGroup(owner, "G", first, second, invitee));
        acceptGroupInvite(first, groupId).andExpect(status().isOk());
        CLOCK.advance(java.time.Duration.ofMinutes(1));
        acceptGroupInvite(second, groupId).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, owner.id()).with(owner.auth()))
                .andExpect(status().isNoContent());

        getGroup(second, groupId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(first.id().toString()))
                .andExpect(jsonPath("$.members[0].user.id").value(first.id().toString()))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.members", hasSize(3)));
        getGroup(first, groupId).andExpect(jsonPath("$.role").value("OWNER"));
        getGroup(owner, groupId).andExpect(status().isNotFound());
        // The new owner can now edit; the old one cannot.
        mockMvc.perform(patch("/api/v1/groups/{id}", groupId).with(first.auth())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Renamed\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void lastActiveMemberLeavingDeletesTheGroupEvenWithPendingInvites() throws Exception {
        TestUser owner = user();
        TestUser invitee = user();
        befriend(owner, invitee);
        UUID groupId = id(createGroup(owner, "G", invitee));

        mockMvc.perform(delete("/api/v1/groups/{id}/members/{userId}", groupId, owner.id()).with(owner.auth()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from app.groups where id = ?", Integer.class, groupId)).isZero();
        getGroup(invitee, groupId).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------------- invites

    @Test
    void acceptingAnInviteMakesYouActive() throws Exception {
        TestUser owner = user();
        TestUser invitee = user();
        befriend(owner, invitee);
        UUID groupId = id(createGroup(owner, "G", invitee));

        acceptGroupInvite(invitee, groupId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.members[1].status").value("ACTIVE"))
                .andExpect(jsonPath("$.members[1].joinedAt").isNotEmpty());
        // Accepting again is harmless.
        acceptGroupInvite(invitee, groupId).andExpect(status().isOk()).andExpect(jsonPath("$.memberStatus").value("ACTIVE"));
        acceptGroupInvite(user(), groupId).andExpect(status().isNotFound());
    }

    @Test
    void decliningRemovesTheInvite() throws Exception {
        TestUser owner = user();
        TestUser invitee = user();
        befriend(owner, invitee);
        UUID groupId = id(createGroup(owner, "G", invitee));

        mockMvc.perform(post("/api/v1/groups/{id}/invites/decline", groupId).with(invitee.auth()))
                .andExpect(status().isNoContent());

        getGroup(invitee, groupId).andExpect(status().isNotFound());
        getGroup(owner, groupId).andExpect(jsonPath("$.members", hasSize(1)));
        mockMvc.perform(post("/api/v1/groups/{id}/invites/decline", groupId).with(invitee.auth()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/groups/{id}/invites/decline", groupId).with(owner.auth()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You have already joined this group. Leave it instead."));
    }

    // ------------------------------------------------------------------------------- deletion

    @Test
    void deletingTheOwnersAccountHandsTheGroupOver() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = activeGroup(owner, member);

        mockMvc.perform(delete("/api/v1/me").with(owner.auth())).andExpect(status().isNoContent());

        getGroup(member, groupId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(member.id().toString()))
                .andExpect(jsonPath("$.role").value("OWNER"))
                .andExpect(jsonPath("$.members", hasSize(1)));
    }

    @Test
    void deletingTheOnlyActiveMembersAccountDeletesTheGroup() throws Exception {
        TestUser owner = user();
        TestUser invitee = user();
        befriend(owner, invitee);
        UUID groupId = id(createGroup(owner, "G", invitee));

        mockMvc.perform(delete("/api/v1/me").with(owner.auth())).andExpect(status().isNoContent());

        getGroup(invitee, groupId).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/groups").with(invitee.auth())).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void memberUserSummariesCarryFriendshipRelativeToTheViewer() throws Exception {
        TestUser owner = user();
        TestUser a = user();
        TestUser b = user();
        UUID groupId = activeGroup(owner, a, b);

        getGroup(a, groupId)
                .andExpect(jsonPath("$.members[?(@.user.id == '" + owner.id() + "')].user.friendship",
                        containsInAnyOrder("FRIENDS")))
                .andExpect(jsonPath("$.members[?(@.user.id == '" + b.id() + "')].user.friendship",
                        containsInAnyOrder("NONE")))
                .andExpect(jsonPath("$.members[0]", not(hasKey("email"))))
                .andExpect(jsonPath("$.members[0].user", not(hasKey("email"))))
                .andExpect(jsonPath("$.members[0].role", is("OWNER")));
    }

    private static UUID id(org.springframework.test.web.servlet.ResultActions created) throws Exception {
        return UUID.fromString(JsonPath.read(json(created.andExpect(status().isCreated())), "$.id"));
    }
}

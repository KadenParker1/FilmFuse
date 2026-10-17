package com.pm.filmfuse.group;

import com.pm.filmfuse.reco.AbstractTasteIntegrationTest;
import com.pm.filmfuse.reco.ml.MlGroupMember;
import com.pm.filmfuse.reco.ml.MlGroupRecommendation;
import com.pm.filmfuse.reco.ml.MlGroupRecommendationRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Since Phase 1b sessions snapshot hasVector from user_interest_vectors and send members' vectors to the ML service. */
class SessionMemberVectorIntegrationTest extends AbstractTasteIntegrationTest {

    @Test
    void membersWithAVectorAreFlaggedAndTheirVectorsSent() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = activeGroup(owner, member);
        setGenres(owner, "[{\"genreId\": 28, \"score\": 9}]");
        buildVector(owner);
        // A dirty member without any built vector yet still counts as "no vector".
        setGenres(member, "[{\"genreId\": 35, \"score\": 9}]");

        int genre = uniqueGenre();
        int movie = movie(genre, "PG", 10);
        when(mlClient.isConfigured()).thenReturn(true);
        when(mlClient.groupRecommendations(any())).thenReturn(List.of(
                new MlGroupRecommendation(movie, 7.0, Map.of(owner.id(), 7.0, member.id(), 7.0), "Both")));

        String body = json(createGroupSession(owner, groupId, genre).andExpect(status().isCreated()));
        assertThat((List<Boolean>) com.jayway.jsonpath.JsonPath.read(body,
                "$.members[?(@.user.id == '" + owner.id() + "')].hasVector")).containsExactly(true);
        assertThat((List<Boolean>) com.jayway.jsonpath.JsonPath.read(body,
                "$.members[?(@.user.id == '" + member.id() + "')].hasVector")).containsExactly(false);

        ArgumentCaptor<MlGroupRecommendationRequest> captor = ArgumentCaptor.forClass(MlGroupRecommendationRequest.class);
        verify(mlClient).groupRecommendations(captor.capture());
        Map<UUID, MlGroupMember> members = new java.util.HashMap<>();
        captor.getValue().members().forEach(m -> members.put(m.userId(), m));
        assertThat(members.get(owner.id()).vector()).isNotNull();
        assertThat(members.get(owner.id()).vector().interpretable()).hasSize(27).startsWith(9.0);
        assertThat(members.get(member.id()).vector()).isNull();
    }

    @Test
    void aDirtyMemberWithAnOlderVectorStillSendsIt() throws Exception {
        TestUser owner = user();
        TestUser member = user();
        UUID groupId = activeGroup(owner, member);
        setGenres(member, "[{\"genreId\": 28, \"score\": 3}]");
        buildVector(member);
        setGenres(member, "[{\"genreId\": 28, \"score\": 10}]");

        int genre = uniqueGenre();
        movie(genre, "PG", 10);
        createGroupSession(owner, groupId, genre).andExpect(status().isCreated())
                .andExpect(jsonPath("$.members[?(@.user.id == '" + member.id() + "')].hasVector").value(true));
    }
}

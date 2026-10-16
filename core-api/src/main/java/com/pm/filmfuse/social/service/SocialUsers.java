package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.model.SocialProfile;
import com.pm.filmfuse.social.model.SocialUser;
import com.pm.filmfuse.social.repository.SocialProfileRepository;
import com.pm.filmfuse.user.model.AccountStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Looking up other users for the social services, with the visibility rules applied. */
@Component
public class SocialUsers {

    static final String USER_NOT_FOUND = "User not found.";

    private final SocialProfileRepository profiles;
    private final SocialGraphService graph;

    public SocialUsers(SocialProfileRepository profiles, SocialGraphService graph) {
        this.profiles = profiles;
        this.graph = graph;
    }

    /** Any existing user, suspended or not; 404 for an unknown id. */
    public SocialProfile requireExists(UUID id) {
        return profiles.findProfile(id).orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
    }

    /** An active user; 404 for an unknown id or a suspended account. Blocks are not considered. */
    public SocialProfile requireActive(UUID id) {
        return profiles.findProfile(id)
                .filter(profile -> profile.status() != AccountStatus.SUSPENDED)
                .orElseThrow(() -> new NotFoundException(USER_NOT_FOUND));
    }

    /**
     * {@link #requireActive}, and also 404 when {@code target} has blocked {@code viewer} - so a
     * blocked person cannot tell a block from a deleted account.
     */
    public SocialProfile requireVisible(UUID viewer, UUID target) {
        SocialProfile profile = requireActive(target);
        if (graph.hasBlocked(target, viewer)) {
            throw new NotFoundException(USER_NOT_FOUND);
        }
        return profile;
    }

    /** Summaries of {@code ids} in the given order, with each one's state relative to {@code viewer}. */
    public List<UserSummaryDTO> summaries(UUID viewer, List<UUID> ids) {
        Map<UUID, SocialUser> users = profiles.findUsers(ids);
        Map<UUID, FriendshipState> states = graph.statesFor(viewer, users.keySet());
        return ids.stream()
                .map(users::get)
                .filter(Objects::nonNull)
                .map(user -> SocialMapper.toSummaryDTO(user, states.get(user.id())))
                .toList();
    }

    /** {@link #summaries} for users already loaded. */
    public List<UserSummaryDTO> summariesOf(UUID viewer, Collection<SocialUser> users) {
        Map<UUID, FriendshipState> states = graph.statesFor(viewer, users.stream().map(SocialUser::id).toList());
        return users.stream().map(user -> SocialMapper.toSummaryDTO(user, states.get(user.id()))).toList();
    }
}

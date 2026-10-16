package com.pm.filmfuse.social.mapper;

import com.pm.filmfuse.social.dto.FriendDTO;
import com.pm.filmfuse.social.dto.FriendRequestDTO;
import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.dto.PublicProfileDTO;
import com.pm.filmfuse.social.dto.RequestDirection;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.model.FriendEdge;
import com.pm.filmfuse.social.model.PendingRequest;
import com.pm.filmfuse.social.model.SocialProfile;
import com.pm.filmfuse.social.model.SocialUser;

public final class SocialMapper {

    private SocialMapper() {
    }

    public static UserSummaryDTO toSummaryDTO(SocialUser user, FriendshipState friendship) {
        return new UserSummaryDTO(user.id(), user.username(), user.displayName(), user.profileImageUrl(), friendship);
    }

    public static FriendDTO toFriendDTO(FriendEdge edge) {
        SocialUser friend = edge.friend();
        return new FriendDTO(friend.id(), friend.username(), friend.displayName(), friend.profileImageUrl(),
                edge.explicitClose(), toDouble(edge.closeness()), edge.friendsSince());
    }

    public static FriendRequestDTO toRequestDTO(PendingRequest request) {
        return request.outgoing()
                ? new FriendRequestDTO(toSummaryDTO(request.other(), FriendshipState.REQUEST_SENT),
                        RequestDirection.OUTGOING, request.createdAt())
                : new FriendRequestDTO(toSummaryDTO(request.other(), FriendshipState.REQUEST_RECEIVED),
                        RequestDirection.INCOMING, request.createdAt());
    }

    public static PublicProfileDTO toPublicProfileDTO(SocialProfile profile, FriendshipState friendship,
                                                      boolean closeFriend, int mutualFriendCount,
                                                      int friendCount) {
        SocialUser user = profile.user();
        // tasteOverlap stays null until interest vectors exist (Phase 1b).
        return new PublicProfileDTO(user.id(), user.username(), user.displayName(), user.profileImageUrl(),
                profile.bio(), friendship, closeFriend, mutualFriendCount, friendCount, null);
    }

    /** {@code real} to {@code double} without float noise (0.8f widens to 0.800000011920929). */
    public static double toDouble(float value) {
        return Double.parseDouble(Float.toString(value));
    }
}

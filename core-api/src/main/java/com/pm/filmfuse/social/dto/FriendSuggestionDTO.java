package com.pm.filmfuse.social.dto;

import java.util.List;

/**
 * A friend-of-a-friend. {@code mutualFriends} holds at most three of the {@code mutualFriendCount}
 * people in common, closest first. Mirrored by hand in client/types/api.ts.
 */
public record FriendSuggestionDTO(UserSummaryDTO user, int mutualFriendCount, List<UserSummaryDTO> mutualFriends) {
}

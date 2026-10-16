package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.social.dto.FriendshipState;
import com.pm.filmfuse.social.dto.PublicProfileDTO;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.model.FriendEdge;
import com.pm.filmfuse.social.model.SocialProfile;
import com.pm.filmfuse.social.model.SocialUser;
import com.pm.filmfuse.social.repository.SocialProfileRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Finding people (search) and viewing them (public profile). */
@Service
public class UserSearchService {

    static final int PAGE_SIZE = 20;
    static final int QUERY_MIN = 2;
    static final int QUERY_MAX = 50;
    static final String QUERY_TOO_SHORT = "Enter at least 2 characters to search.";

    private final SocialProfileRepository profiles;
    private final SocialGraphService graph;
    private final SocialUsers users;

    public UserSearchService(SocialProfileRepository profiles, SocialGraphService graph, SocialUsers users) {
        this.profiles = profiles;
        this.graph = graph;
        this.users = users;
    }

    /**
     * Case-insensitive prefix match on username or display name, ordered by username. Only users
     * with a username appear; the caller and anyone who blocked the caller never do. People the
     * caller blocked do appear, as BLOCKED, so they can be found and unblocked.
     */
    public PageDTO<UserSummaryDTO> search(UUID callerId, String rawQuery, String cursor) {
        String query = rawQuery.trim();
        if (query.length() < QUERY_MIN) {
            throw new BadRequestException(QUERY_TOO_SHORT);
        }
        String after = cursor == null || cursor.isBlank() ? null : SocialCursors.decodeSearch(cursor);
        Set<UUID> excluded = new HashSet<>(graph.blockersOf(callerId));
        excluded.add(callerId);

        List<SocialUser> rows = profiles.search(escapeLike(query.toLowerCase(Locale.ROOT)), excluded, after,
                PAGE_SIZE + 1);
        boolean more = rows.size() > PAGE_SIZE;
        List<SocialUser> page = more ? rows.subList(0, PAGE_SIZE) : rows;
        String next = more ? SocialCursors.encodeSearch(page.getLast().username()) : null;
        return new PageDTO<>(users.summariesOf(callerId, page), next);
    }

    /** 404 for an unknown or suspended user, or one who has blocked the caller. */
    public PublicProfileDTO profile(UUID callerId, UUID userId) {
        SocialProfile profile = users.requireVisible(callerId, userId);
        FriendshipState friendship = graph.stateOf(callerId, userId);
        boolean closeFriend = friendship == FriendshipState.FRIENDS
                && graph.friend(callerId, userId).map(FriendEdge::explicitClose).orElse(false);
        return SocialMapper.toPublicProfileDTO(profile, friendship, closeFriend,
                graph.mutualFriendCount(callerId, userId),
                graph.friendCount(userId));
    }

    /** Makes {@code %}, {@code _} and the escape character itself literal in a LIKE pattern. */
    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

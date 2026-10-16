package com.pm.filmfuse.social.service;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.social.dto.FriendDTO;
import com.pm.filmfuse.social.dto.FriendSuggestionDTO;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.mapper.SocialMapper;
import com.pm.filmfuse.social.model.FriendEdge;
import com.pm.filmfuse.social.model.Suggestion;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The caller's friends list, the close-friend flag, unfriending and friend suggestions. */
@Service
public class FriendService {

    static final int PAGE_SIZE = 50;
    /** How many of the shared friends a suggestion shows. */
    static final int MUTUAL_SAMPLE = 3;
    static final String NOT_FRIENDS = "You are not friends with this person.";

    private final SocialGraphService graph;
    private final SocialUsers users;

    public FriendService(SocialGraphService graph, SocialUsers users) {
        this.graph = graph;
        this.users = users;
    }

    /** Close friends first, then by closeness, then username. */
    public PageDTO<FriendDTO> list(UUID callerId, String cursor) {
        var after = cursor == null || cursor.isBlank() ? null : SocialCursors.decodeFriends(cursor);
        List<FriendEdge> rows = graph.friends(callerId, after, PAGE_SIZE + 1);
        boolean more = rows.size() > PAGE_SIZE;
        List<FriendEdge> page = more ? rows.subList(0, PAGE_SIZE) : rows;
        String next = more ? SocialCursors.encodeFriends(page.getLast()) : null;
        return new PageDTO<>(page.stream().map(SocialMapper::toFriendDTO).toList(), next);
    }

    /** 404 when they are not friends. */
    public FriendDTO update(UUID callerId, UUID friendId, boolean closeFriend) {
        return graph.setCloseFriend(callerId, friendId, closeFriend)
                .map(SocialMapper::toFriendDTO)
                .orElseThrow(() -> new NotFoundException(NOT_FRIENDS));
    }

    /** Idempotent. */
    public void unfriend(UUID callerId, UUID friendId) {
        graph.unfriend(callerId, friendId);
    }

    public List<FriendSuggestionDTO> suggestions(UUID callerId, int limit) {
        List<Suggestion> suggestions = graph.suggestions(callerId, limit, MUTUAL_SAMPLE);
        if (suggestions.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        suggestions.forEach(s -> {
            ids.add(s.candidateId());
            ids.addAll(s.sampleMutualFriendIds());
        });
        Map<UUID, UserSummaryDTO> summaries = users.summaries(callerId, List.copyOf(ids)).stream()
                .collect(Collectors.toMap(UserSummaryDTO::id, Function.identity()));
        return suggestions.stream()
                .filter(s -> summaries.containsKey(s.candidateId()))
                .map(s -> new FriendSuggestionDTO(
                        summaries.get(s.candidateId()),
                        s.mutualFriendCount(),
                        s.sampleMutualFriendIds().stream().map(summaries::get).filter(Objects::nonNull).toList()))
                .toList();
    }
}

package com.pm.filmfuse.group.service;

import com.pm.filmfuse.group.dto.SessionSummaryDTO;
import com.pm.filmfuse.group.model.Session;
import com.pm.filmfuse.group.repository.SessionRepository;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.service.SocialGraphService;
import com.pm.filmfuse.social.service.SocialUsers;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Builds {@link SessionSummaryDTO}s for one viewer in a fixed number of queries: picked movies,
 * voter counts, whether the viewer voted, and a member preview that leaves out anyone blocked
 * either way with the viewer (SECURITY.md #15). Counts include everyone.
 */
@Component
public class SessionSummaries {

    static final int PREVIEW_SIZE = 5;

    private final SessionRepository sessions;
    private final MovieSummaries movieSummaries;
    private final SocialGraphService graph;
    private final SocialUsers users;
    private final Clock clock;

    public SessionSummaries(SessionRepository sessions, MovieSummaries movieSummaries, SocialGraphService graph,
                            SocialUsers users, Clock clock) {
        this.sessions = sessions;
        this.movieSummaries = movieSummaries;
        this.graph = graph;
        this.users = users;
        this.clock = clock;
    }

    /** In the given order. Call inside a (read) transaction. */
    public List<SessionSummaryDTO> of(UUID viewerId, List<Session> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(Session::id).toList();
        Map<Integer, MovieSummaryDTO> movies = movieSummaries.of(
                rows.stream().map(Session::pickedMovieId).filter(Objects::nonNull).toList());
        Map<UUID, Integer> voted = sessions.votedCounts(ids);
        Set<UUID> votedByViewer = sessions.votedIn(viewerId, ids);
        Map<UUID, List<UUID>> members = sessions.membersOf(ids, viewerId);

        Set<UUID> everyone = members.values().stream().flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        everyone.removeAll(graph.blockedEitherWayAmong(viewerId, everyone));
        Map<UUID, UserSummaryDTO> summaries = users.summaries(viewerId, List.copyOf(everyone)).stream()
                .collect(Collectors.toMap(UserSummaryDTO::id, Function.identity(), (a, b) -> a));

        Instant now = clock.instant();
        return rows.stream().map(s -> {
            List<UserSummaryDTO> preview = members.getOrDefault(s.id(), List.of()).stream()
                    .map(summaries::get).filter(Objects::nonNull).limit(PREVIEW_SIZE).toList();
            return new SessionSummaryDTO(s.id(), s.groupId(), s.groupName(), s.status(now),
                    s.pickedMovieId() == null ? null : movies.get(s.pickedMovieId()), s.memberCount(),
                    voted.getOrDefault(s.id(), 0), votedByViewer.contains(s.id()), preview, s.closesAt(),
                    s.endedEarly(), s.createdAt());
        }).toList();
    }
}

package com.pm.filmfuse.group.service;

import com.pm.filmfuse.common.dto.PageDTO;
import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.ConflictException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.common.pagination.CursorCodec;
import com.pm.filmfuse.group.dto.CreateSessionDTO;
import com.pm.filmfuse.group.dto.SessionDTO;
import com.pm.filmfuse.group.dto.SessionFiltersDTO;
import com.pm.filmfuse.group.dto.SessionMemberDTO;
import com.pm.filmfuse.group.dto.SessionResultDTO;
import com.pm.filmfuse.group.dto.SessionStatus;
import com.pm.filmfuse.group.dto.SessionSummaryDTO;
import com.pm.filmfuse.group.dto.Strategy;
import com.pm.filmfuse.group.dto.Vote;
import com.pm.filmfuse.group.dto.VoteTallyDTO;
import com.pm.filmfuse.group.mapper.GroupMapper;
import com.pm.filmfuse.group.model.Group;
import com.pm.filmfuse.group.model.Session;
import com.pm.filmfuse.group.model.SessionMember;
import com.pm.filmfuse.group.model.SessionResult;
import com.pm.filmfuse.group.model.SessionVote;
import com.pm.filmfuse.group.realtime.SessionUpdatedEvent;
import com.pm.filmfuse.group.repository.SessionRepository;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.reco.dto.VectorChangeReason;
import com.pm.filmfuse.reco.model.CandidateCriteria;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.model.GroupRecommendationInput;
import com.pm.filmfuse.reco.model.MovieContent;
import com.pm.filmfuse.reco.model.RankedMovie;
import com.pm.filmfuse.reco.model.UserVectorLookup;
import com.pm.filmfuse.reco.service.ContentFilterService;
import com.pm.filmfuse.reco.service.GroupRecommender;
import com.pm.filmfuse.reco.service.VectorDirtyMarker;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.service.SocialGraphService;
import com.pm.filmfuse.social.service.SocialUsers;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recommendation sessions: create (for a group or an ad-hoc set of friends), view, history, votes,
 * refresh, pick.
 *
 * <p>Only the session's members (snapshotted at creation) can see or touch it; anyone else gets
 * 404. Votes, refresh and pick need the session OPEN (not DECIDED, not older than 24 hours) - 409
 * otherwise. Each of them broadcasts {@code session_updated} after commit.
 *
 * <p>Ranking runs outside any transaction ({@link GroupRecommender} may wait on the ML service);
 * the writes that follow run in a short transaction that re-checks the session under a row lock.
 */
@Service
public class SessionService {

    public static final int K = 20;
    static final int PAGE_SIZE = 20;

    static final String SESSION_NOT_FOUND = "Session not found.";
    static final String GROUP_OR_FRIENDS = "Choose either a group or friends for the session, not both.";
    static final String NEED_GROUP_OR_FRIENDS = "Choose a group or at least one friend for the session.";
    static final String CLOSED = "This session is no longer open.";
    static final String NOT_A_RESULT = "That movie is not in this session's results.";
    static final String VETOED = "That movie was vetoed.";
    static final String OUTSIDE_COMFORT = "This movie is outside the group's comfort settings.";

    private final SessionRepository sessions;
    private final GroupService groupService;
    private final ContentFilterService contentFilter;
    private final GroupRecommender recommender;
    private final UserVectorLookup vectorLookup;
    private final SocialGraphService graph;
    private final SocialUsers users;
    private final MovieSummaries movieSummaries;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final VectorDirtyMarker dirtyMarker;
    private final Clock clock;

    public SessionService(SessionRepository sessions, GroupService groupService, ContentFilterService contentFilter,
                          GroupRecommender recommender, UserVectorLookup vectorLookup, SocialGraphService graph,
                          SocialUsers users, MovieSummaries movieSummaries, ApplicationEventPublisher events,
                          TransactionTemplate transactionTemplate, VectorDirtyMarker dirtyMarker, Clock clock) {
        this.sessions = sessions;
        this.groupService = groupService;
        this.contentFilter = contentFilter;
        this.recommender = recommender;
        this.vectorLookup = vectorLookup;
        this.graph = graph;
        this.users = users;
        this.movieSummaries = movieSummaries;
        this.events = events;
        this.tx = transactionTemplate;
        this.readTx = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.readTx.setReadOnly(true);
        this.dirtyMarker = dirtyMarker;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------- create

    public SessionDTO create(UUID callerId, CreateSessionDTO request) {
        boolean hasGroup = request.groupId() != null;
        boolean hasFriends = request.memberIds() != null;
        if (hasGroup && hasFriends) {
            throw new BadRequestException(GROUP_OR_FRIENDS);
        }
        if (!hasGroup && !hasFriends) {
            throw new BadRequestException(NEED_GROUP_OR_FRIENDS);
        }

        List<UUID> memberIds;
        ContentFilter cap;
        String groupName = null;
        UUID groupId = request.groupId();
        if (hasGroup) {
            Group group = groupService.requireActiveMember(callerId, groupId);
            memberIds = groupService.activeMemberIds(groupId);
            cap = group.cap();
            groupName = group.name();
        } else {
            List<UUID> friends = groupService.requireInvitableFriends(callerId, request.memberIds());
            if (friends.isEmpty()) {
                throw new BadRequestException(NEED_GROUP_OR_FRIENDS);
            }
            memberIds = Stream.concat(Stream.of(callerId), friends.stream()).toList();
            cap = null;
        }
        SessionFiltersDTO filters = request.filters() == null ? SessionFiltersDTO.NONE : request.filters();
        Strategy strategy = request.strategy() == null ? Strategy.MEAN : request.strategy();

        ContentFilter effective = contentFilter.effectiveFor(memberIds, cap);
        Set<UUID> withVector = vectorLookup.vectorsOf(memberIds).keySet();
        List<RankedMovie> ranked = recommender.recommend(input(memberIds, effective, filters, strategy, Set.of()));

        UUID sessionId = UUID.randomUUID();
        Instant now = clock.instant();
        String startedIn = groupName;
        tx.executeWithoutResult(status -> {
            sessions.insertSession(sessionId, groupId, callerId, filters, effective, strategy, now);
            Map<UUID, Boolean> hasVector = new LinkedHashMap<>();
            memberIds.forEach(id -> hasVector.put(id, withVector.contains(id)));
            sessions.insertMembers(sessionId, hasVector);
            sessions.insertResults(sessionId, ranked, 1, now);
            events.publishEvent(new SessionStartedEvent(sessionId, callerId, startedIn, List.copyOf(memberIds)));
        });
        return get(callerId, sessionId);
    }

    // ------------------------------------------------------------------------------- reads

    public SessionDTO get(UUID callerId, UUID sessionId) {
        return readTx.execute(status -> toDTO(callerId, requireMember(callerId, sessionId)));
    }

    /** The caller's sessions, newest first; only those of {@code groupId} when given. */
    public PageDTO<SessionSummaryDTO> list(UUID callerId, UUID groupId, String cursor) {
        Instant afterCreatedAt = null;
        UUID afterId = null;
        if (cursor != null && !cursor.isBlank()) {
            List<String> parts = CursorCodec.decode(cursor, 2);
            try {
                afterCreatedAt = Instant.parse(parts.get(0));
                afterId = UUID.fromString(parts.get(1));
            } catch (DateTimeParseException | IllegalArgumentException ex) {
                throw new BadRequestException(CursorCodec.INVALID_CURSOR);
            }
        }
        Instant after = afterCreatedAt;
        UUID afterSession = afterId;
        return readTx.execute(status -> {
            List<Session> rows = sessions.listFor(callerId, groupId, after, afterSession, PAGE_SIZE + 1);
            boolean more = rows.size() > PAGE_SIZE;
            List<Session> page = more ? rows.subList(0, PAGE_SIZE) : rows;
            Map<Integer, MovieSummaryDTO> movies = movieSummaries.of(
                    page.stream().map(Session::pickedMovieId).filter(Objects::nonNull).toList());
            Instant now = clock.instant();
            List<SessionSummaryDTO> items = page.stream()
                    .map(s -> GroupMapper.toSummaryDTO(s, s.pickedMovieId() == null ? null : movies.get(s.pickedMovieId()), now))
                    .toList();
            String next = more
                    ? CursorCodec.encode(List.of(page.getLast().createdAt().toString(), page.getLast().id().toString()))
                    : null;
            return new PageDTO<>(items, next);
        });
    }

    // ------------------------------------------------------------------------------- votes

    public SessionDTO vote(UUID callerId, UUID sessionId, int movieId, Vote vote) {
        return tx.execute(status -> {
            Session session = requireOpenResult(callerId, sessionId, movieId);
            sessions.upsertVote(session.id(), callerId, movieId, vote, clock.instant());
            events.publishEvent(new SessionUpdatedEvent(sessionId, SessionUpdatedEvent.VOTE));
            return toDTO(callerId, session);
        });
    }

    public SessionDTO clearVote(UUID callerId, UUID sessionId, int movieId) {
        return tx.execute(status -> {
            Session session = requireOpenResult(callerId, sessionId, movieId);
            sessions.deleteVote(session.id(), callerId, movieId);
            events.publishEvent(new SessionUpdatedEvent(sessionId, SessionUpdatedEvent.VOTE));
            return toDTO(callerId, session);
        });
    }

    // ------------------------------------------------------------------------------- refresh / pick

    /**
     * Appends up to {@value #K} new results, never repeating one already shown (vetoed ones
     * included). Recomputes the effective filter first, so comfort changes since creation apply.
     */
    public SessionDTO refresh(UUID callerId, UUID sessionId) {
        Session session = readTx.execute(status -> requireOpen(requireMember(callerId, sessionId)));
        List<UUID> memberIds = sessions.memberIds(sessionId);
        ContentFilter effective = contentFilter.effectiveFor(memberIds, groupService.capOf(session.groupId()));
        Set<Integer> shown = sessions.findResults(sessionId).stream().map(SessionResult::movieId)
                .collect(Collectors.toSet());
        List<RankedMovie> ranked = recommender.recommend(
                input(memberIds, effective, session.filters(), session.strategy(), shown));

        return tx.execute(status -> {
            sessions.lockSession(sessionId);
            Session locked = requireOpen(requireMember(callerId, sessionId));
            List<SessionResult> current = sessions.findResults(sessionId);
            Set<Integer> present = current.stream().map(SessionResult::movieId).collect(Collectors.toSet());
            List<RankedMovie> fresh = ranked.stream().filter(r -> !present.contains(r.movieId())).toList();
            int nextRank = current.stream().mapToInt(SessionResult::rank).max().orElse(0) + 1;
            sessions.insertResults(sessionId, fresh, nextRank, clock.instant());
            sessions.updateEffectiveFilter(sessionId, effective);
            events.publishEvent(new SessionUpdatedEvent(sessionId, SessionUpdatedEvent.REFRESH));
            return toDTO(callerId, sessions.findSession(locked.id()).orElseThrow());
        });
    }

    /** Any member may pick a (non-vetoed) result; the session is DECIDED and everyone's watch history updated. */
    public SessionDTO pick(UUID callerId, UUID sessionId, int movieId) {
        return tx.execute(status -> {
            sessions.lockSession(sessionId);
            Session session = requireOpen(requireMember(callerId, sessionId));
            if (!sessions.hasResult(sessionId, movieId)) {
                throw new BadRequestException(NOT_A_RESULT);
            }
            if (sessions.isVetoed(sessionId, movieId)) {
                throw new BadRequestException(VETOED);
            }
            MovieContent movie = contentFilter.contentOf(List.of(movieId)).get(movieId);
            if (movie == null || !currentFilter(session).isEligible(movie)) {
                throw new BadRequestException(OUTSIDE_COMFORT);
            }
            Instant now = clock.instant();
            sessions.decide(sessionId, movieId, now);
            List<UUID> watchers = sessions.memberIds(sessionId);
            sessions.insertWatchHistory(watchers, movieId, sessionId, now);
            // A pick is a taste signal for everyone who watches it (the ML service reads watch_history).
            dirtyMarker.markDirty(watchers, VectorChangeReason.PICK);
            events.publishEvent(new SessionUpdatedEvent(sessionId, SessionUpdatedEvent.PICK));
            return toDTO(callerId, sessions.findSession(session.id()).orElseThrow());
        });
    }

    // ------------------------------------------------------------------------------- helpers

    private GroupRecommendationInput input(List<UUID> memberIds, ContentFilter effective, SessionFiltersDTO filters,
                                           Strategy strategy, Set<Integer> excluded) {
        return new GroupRecommendationInput(memberIds, closeness(memberIds), effective,
                new CandidateCriteria(filters.maxRuntime(), filters.minYear(), filters.genreIds()),
                excluded, strategy.mlValue(), K);
    }

    /** {@code closeness[i][j]}: how close member i is to member j (0 when not friends; 0 on the diagonal). */
    private double[][] closeness(List<UUID> memberIds) {
        return graph.closenessMatrix(memberIds);
    }

    private Session requireMember(UUID callerId, UUID sessionId) {
        Session session = sessions.findSession(sessionId).orElseThrow(() -> new NotFoundException(SESSION_NOT_FOUND));
        if (!sessions.isMember(sessionId, callerId)) {
            throw new NotFoundException(SESSION_NOT_FOUND);
        }
        return session;
    }

    private Session requireOpen(Session session) {
        if (session.status(clock.instant()) != SessionStatus.OPEN) {
            throw new ConflictException(CLOSED);
        }
        return session;
    }

    /** Member, OPEN (409) and the movie is one of the results (404). */
    private Session requireOpenResult(UUID callerId, UUID sessionId, int movieId) {
        Session session = requireOpen(requireMember(callerId, sessionId));
        if (!sessions.hasResult(sessionId, movieId)) {
            throw new NotFoundException(NOT_A_RESULT);
        }
        return session;
    }

    /**
     * The comfort filter as it stands now: the session members' current settings plus the group's
     * current cap. Comfort settings are hard constraints even mid-session.
     */
    private ContentFilter currentFilter(Session session) {
        return contentFilter.effectiveFor(sessions.memberIds(session.id()), groupService.capOf(session.groupId()));
    }

    /**
     * OPEN sessions are shown under {@link #currentFilter}: results that no longer pass it are left
     * out (and cannot be picked). DECIDED / EXPIRED sessions are history and are shown as stored.
     */
    private SessionDTO toDTO(UUID callerId, Session session) {
        Instant now = clock.instant();
        SessionStatus status = session.status(now);
        List<SessionMember> members = sessions.findMembers(session.id());
        List<SessionResult> results = sessions.findResults(session.id());
        ContentFilter effective = session.effectiveFilter();
        if (status == SessionStatus.OPEN) {
            effective = currentFilter(session);
            ContentFilter filter = effective;
            Map<Integer, MovieContent> content = contentFilter.contentOf(
                    results.stream().map(SessionResult::movieId).toList());
            results = results.stream()
                    .filter(r -> content.containsKey(r.movieId()) && filter.isEligible(content.get(r.movieId())))
                    .toList();
        }
        List<SessionVote> votes = sessions.findVotes(session.id());

        Set<UUID> userIds = new HashSet<>();
        members.forEach(m -> userIds.add(m.userId()));
        if (session.createdBy() != null) {
            userIds.add(session.createdBy());
        }
        Map<UUID, UserSummaryDTO> summaries = users.summaries(callerId, List.copyOf(userIds)).stream()
                .collect(Collectors.toMap(UserSummaryDTO::id, Function.identity(), (a, b) -> a));

        List<Integer> movieIds = new ArrayList<>(results.stream().map(SessionResult::movieId).toList());
        if (session.pickedMovieId() != null) {
            movieIds.add(session.pickedMovieId());
        }
        Map<Integer, MovieSummaryDTO> movies = movieSummaries.of(movieIds);

        Map<Integer, List<SessionVote>> votesByMovie = new HashMap<>();
        votes.forEach(v -> votesByMovie.computeIfAbsent(v.movieId(), k -> new ArrayList<>()).add(v));

        List<SessionResultDTO> resultDTOs = results.stream()
                .map(r -> toResultDTO(r, movies.get(r.movieId()), votesByMovie.getOrDefault(r.movieId(), List.of()),
                        callerId))
                .filter(r -> r.movie() != null)
                .sorted(Comparator.comparing((SessionResultDTO r) -> r.votes().vetoed()).thenComparingInt(SessionResultDTO::rank))
                .toList();
        List<SessionMemberDTO> memberDTOs = members.stream()
                .filter(m -> summaries.containsKey(m.userId()))
                .map(m -> new SessionMemberDTO(summaries.get(m.userId()), m.hasVector(), m.voted()))
                .toList();

        return new SessionDTO(session.id(), session.groupId(), session.groupName(),
                session.createdBy() == null ? null : summaries.get(session.createdBy()),
                status, session.strategy(), session.filters(),
                effective.toDTO(), memberDTOs, resultDTOs,
                session.pickedMovieId() == null ? null : movies.get(session.pickedMovieId()), session.createdAt());
    }

    private static SessionResultDTO toResultDTO(SessionResult result, MovieSummaryDTO movie, List<SessionVote> votes,
                                                UUID callerId) {
        int up = 0;
        int down = 0;
        boolean vetoed = false;
        Vote mine = null;
        for (SessionVote vote : votes) {
            switch (vote.vote()) {
                case UP -> up++;
                case DOWN -> down++;
                case VETO -> vetoed = true;
            }
            if (vote.userId().equals(callerId)) {
                mine = vote.vote();
            }
        }
        return new SessionResultDTO(result.rank(), movie, result.certification(), result.groupScore(),
                result.memberScores(), result.explanation(), new VoteTallyDTO(up, down, vetoed), mine);
    }
}

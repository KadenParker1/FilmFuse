package com.pm.filmfuse.group.service;

import com.pm.filmfuse.common.exception.BadRequestException;
import com.pm.filmfuse.common.exception.ConflictException;
import com.pm.filmfuse.common.exception.ForbiddenException;
import com.pm.filmfuse.common.exception.NotFoundException;
import com.pm.filmfuse.group.dto.AddMembersDTO;
import com.pm.filmfuse.group.dto.CreateGroupDTO;
import com.pm.filmfuse.group.dto.GroupDetailDTO;
import com.pm.filmfuse.group.dto.GroupMemberDTO;
import com.pm.filmfuse.group.dto.GroupRole;
import com.pm.filmfuse.group.dto.GroupSummaryDTO;
import com.pm.filmfuse.group.dto.MemberStatus;
import com.pm.filmfuse.group.dto.SessionSummaryDTO;
import com.pm.filmfuse.group.dto.UpdateGroupDTO;
import com.pm.filmfuse.group.mapper.GroupMapper;
import com.pm.filmfuse.group.model.Group;
import com.pm.filmfuse.group.model.GroupListing;
import com.pm.filmfuse.group.model.GroupMember;
import com.pm.filmfuse.group.model.Session;
import com.pm.filmfuse.group.repository.GroupRepository;
import com.pm.filmfuse.group.repository.SessionRepository;
import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.movie.service.MovieSummaries;
import com.pm.filmfuse.reco.model.ContentFilter;
import com.pm.filmfuse.reco.service.ContentFilterService;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import com.pm.filmfuse.social.service.SocialGraphService;
import com.pm.filmfuse.social.service.SocialUsers;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved groups: create, list, view, edit (owner), delete (owner), invite, remove / leave, and
 * answering invites.
 *
 * <p>Visibility: ACTIVE and INVITED members see the group; everyone else - including people who
 * declined or were removed - gets 404, so a group's existence is never revealed. An INVITED member
 * who tries something that needs an ACTIVE one gets 403.
 *
 * <p>Invites go to the inviter's own friends only (neither may have blocked the other). A group
 * holds at most {@value #MAX_MEMBERS} people, invited ones included. When the owner leaves,
 * ownership passes to the earliest-joined ACTIVE member; when the last ACTIVE member leaves, the
 * group is deleted. Every membership change locks the group row first.
 */
@Service
public class GroupService {

    public static final int MAX_MEMBERS = 20;
    static final int PREVIEW_SIZE = 5;
    static final int RECENT_SESSIONS = 5;

    static final String GROUP_NOT_FOUND = "Group not found.";
    static final String MEMBER_NOT_FOUND = "That person is not in this group.";
    static final String FRIENDS_ONLY = "You can only add your friends to a group.";
    static final String GROUP_FULL = "A group can have at most " + MAX_MEMBERS + " members.";
    static final String OWNER_ONLY = "Only the group's owner can do that.";
    static final String JOIN_FIRST = "Accept the group invite first.";
    static final String ALREADY_JOINED = "You have already joined this group. Leave it instead.";

    private final GroupRepository groups;
    private final SessionRepository sessions;
    private final SocialGraphService graph;
    private final SocialUsers users;
    private final ContentFilterService contentFilter;
    private final MovieSummaries movieSummaries;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public GroupService(GroupRepository groups, SessionRepository sessions, SocialGraphService graph, SocialUsers users,
                        ContentFilterService contentFilter, MovieSummaries movieSummaries,
                        ApplicationEventPublisher events, Clock clock) {
        this.groups = groups;
        this.sessions = sessions;
        this.graph = graph;
        this.users = users;
        this.contentFilter = contentFilter;
        this.movieSummaries = movieSummaries;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------- endpoints

    @Transactional
    public GroupDetailDTO create(UUID callerId, CreateGroupDTO request) {
        List<UUID> invitees = requireInvitableFriends(callerId, request.memberIds());
        Instant now = clock.instant();
        Group group = new Group(UUID.randomUUID(), request.name(), request.emoji(), request.color(), callerId,
                ContentFilter.UNLIMITED, now);
        groups.insertGroup(group);
        groups.insertMembers(group.id(), List.of(callerId), GroupRole.OWNER, MemberStatus.ACTIVE, null, now);
        groups.insertMembers(group.id(), invitees, GroupRole.MEMBER, MemberStatus.INVITED, callerId, now);
        publishInvites(group.id(), group.name(), callerId, invitees);
        return detail(callerId, group.id());
    }

    /** ACTIVE memberships first (most recent session first), then pending invites. */
    @Transactional(readOnly = true)
    public List<GroupSummaryDTO> list(UUID callerId) {
        List<GroupListing> listings = groups.listFor(callerId);
        Map<UUID, List<UUID>> previews = groups.activePreview(
                listings.stream().map(listing -> listing.group().id()).toList(), PREVIEW_SIZE);
        Map<UUID, UserSummaryDTO> summaries = summaries(callerId,
                previews.values().stream().flatMap(List::stream).collect(Collectors.toCollection(LinkedHashSet::new)));
        return listings.stream().map(listing -> {
            Group group = listing.group();
            List<UserSummaryDTO> preview = previews.getOrDefault(group.id(), List.of()).stream()
                    .map(summaries::get).filter(Objects::nonNull).toList();
            return new GroupSummaryDTO(group.id(), group.name(), group.emoji(), group.color(),
                    listing.membership().role(), listing.membership().status(), listing.activeCount(), preview,
                    listing.lastSessionAt());
        }).toList();
    }

    @Transactional(readOnly = true)
    public GroupDetailDTO get(UUID callerId, UUID groupId) {
        requireVisible(callerId, groupId);
        return detail(callerId, groupId);
    }

    @Transactional
    public GroupDetailDTO update(UUID callerId, UUID groupId, UpdateGroupDTO request) {
        Group group = lockVisible(callerId, groupId);
        requireOwner(callerId, group);
        Instant now = clock.instant();
        if (request.name() != null || request.emoji() != null || request.color() != null) {
            groups.updateDetails(groupId,
                    request.name() != null ? request.name() : group.name(),
                    request.emoji() != null ? blankToNull(request.emoji()) : group.emoji(),
                    request.color() != null ? blankToNull(request.color()) : group.color(),
                    now);
        }
        if (request.comfortCap() != null) {
            groups.updateCap(groupId, GroupMapper.toCap(request.comfortCap()), now);
        }
        return detail(callerId, groupId);
    }

    @Transactional
    public void delete(UUID callerId, UUID groupId) {
        Group group = lockVisible(callerId, groupId);
        requireOwner(callerId, group);
        groups.deleteGroup(groupId);
    }

    /** Any ACTIVE member may invite their own friends; people already in the group are skipped. */
    @Transactional
    public GroupDetailDTO addMembers(UUID callerId, UUID groupId, AddMembersDTO request) {
        Group group = lockVisible(callerId, groupId);
        requireActive(callerId, groupId);
        Set<UUID> existing = groups.findMembers(groupId).stream().map(GroupMember::userId).collect(Collectors.toSet());
        List<UUID> invitees = requireInvitableFriends(callerId, request.userIds()).stream()
                .filter(id -> !existing.contains(id))
                .toList();
        if (existing.size() + invitees.size() > MAX_MEMBERS) {
            throw new ConflictException(GROUP_FULL);
        }
        groups.insertMembers(groupId, invitees, GroupRole.MEMBER, MemberStatus.INVITED, callerId, clock.instant());
        publishInvites(groupId, group.name(), callerId, invitees);
        return detail(callerId, groupId);
    }

    /** The owner removes someone else; anyone removes themselves (= leave). */
    @Transactional
    public void removeMember(UUID callerId, UUID groupId, UUID userId) {
        Group group = lockVisible(callerId, groupId);
        GroupMember target = groups.findMembership(groupId, userId)
                .orElseThrow(() -> new NotFoundException(MEMBER_NOT_FOUND));
        if (!userId.equals(callerId)) {
            requireOwner(callerId, group);
        }
        leave(group, target);
    }

    @Transactional
    public GroupDetailDTO acceptInvite(UUID callerId, UUID groupId) {
        lockVisible(callerId, groupId);
        GroupMember membership = membership(callerId, groupId);
        if (membership.status() == MemberStatus.INVITED) {
            groups.activate(groupId, callerId, clock.instant());
        }
        return detail(callerId, groupId);
    }

    @Transactional
    public void declineInvite(UUID callerId, UUID groupId) {
        lockVisible(callerId, groupId);
        if (membership(callerId, groupId).isActive()) {
            throw new ConflictException(ALREADY_JOINED);
        }
        groups.deleteMember(groupId, callerId);
    }

    /** Account deletion: leaves every group as if by hand (ownership hand-over, empty groups deleted). */
    @Transactional
    public void removeFromAllGroups(UUID userId) {
        for (UUID groupId : groups.groupIdsOf(userId)) {
            Group group = groups.lockGroup(groupId).orElse(null);
            if (group == null) {
                continue;
            }
            groups.findMembership(groupId, userId).ifPresent(membership -> leave(group, membership));
        }
    }

    // ------------------------------------------------------------------- for SessionService

    /** The group, for a caller who must be an ACTIVE member: 404 if not visible, 403 if only invited. */
    @Transactional(readOnly = true)
    public Group requireActiveMember(UUID callerId, UUID groupId) {
        Group group = requireVisible(callerId, groupId);
        requireActive(callerId, groupId);
        return group;
    }

    /** ACTIVE member ids: owner first, then by join time. */
    @Transactional(readOnly = true)
    public List<UUID> activeMemberIds(UUID groupId) {
        return groups.findMembers(groupId).stream().filter(GroupMember::isActive).map(GroupMember::userId).toList();
    }

    /** The group's current comfort cap; null when the group no longer exists. */
    @Transactional(readOnly = true)
    public ContentFilter capOf(UUID groupId) {
        return groupId == null ? null : groups.findGroup(groupId).map(Group::cap).orElse(null);
    }

    /**
     * {@code ids} minus the caller and duplicates, each checked to be the caller's friend with no
     * block either way; 400 otherwise (the same answer for a stranger, a blocker and an unknown id).
     */
    public List<UUID> requireInvitableFriends(UUID callerId, Collection<UUID> ids) {
        List<UUID> result = new ArrayList<>();
        for (UUID id : new LinkedHashSet<>(ids)) {
            if (id.equals(callerId)) {
                continue;
            }
            if (!graph.areFriends(callerId, id) || graph.isBlockedEitherWay(callerId, id)) {
                throw new BadRequestException(FRIENDS_ONLY);
            }
            result.add(id);
        }
        return result;
    }

    // ------------------------------------------------------------------------------- helpers

    /** New invitees are emailed after commit (GroupEmailNotifier). */
    private void publishInvites(UUID groupId, String groupName, UUID inviterId, List<UUID> invitees) {
        if (!invitees.isEmpty()) {
            events.publishEvent(new GroupInvitesSentEvent(groupId, groupName, inviterId, List.copyOf(invitees)));
        }
    }

    private void leave(Group group, GroupMember member) {
        groups.deleteMember(group.id(), member.userId());
        if (!member.isOwner()) {
            return;
        }
        List<GroupMember> remainingActive = groups.findMembers(group.id()).stream()
                .filter(GroupMember::isActive)
                .sorted((a, b) -> {
                    int byJoin = a.joinedAt().compareTo(b.joinedAt());
                    return byJoin != 0 ? byJoin : a.userId().compareTo(b.userId());
                })
                .toList();
        if (remainingActive.isEmpty()) {
            groups.deleteGroup(group.id());
        } else {
            groups.transferOwnership(group.id(), remainingActive.getFirst().userId(), clock.instant());
        }
    }

    private Group requireVisible(UUID callerId, UUID groupId) {
        Group group = groups.findGroup(groupId).orElseThrow(() -> new NotFoundException(GROUP_NOT_FOUND));
        membership(callerId, groupId);
        return group;
    }

    private Group lockVisible(UUID callerId, UUID groupId) {
        Group group = groups.lockGroup(groupId).orElseThrow(() -> new NotFoundException(GROUP_NOT_FOUND));
        membership(callerId, groupId);
        return group;
    }

    private GroupMember membership(UUID callerId, UUID groupId) {
        return groups.findMembership(groupId, callerId).orElseThrow(() -> new NotFoundException(GROUP_NOT_FOUND));
    }

    private void requireActive(UUID callerId, UUID groupId) {
        if (!membership(callerId, groupId).isActive()) {
            throw new ForbiddenException(JOIN_FIRST);
        }
    }

    private static void requireOwner(UUID callerId, Group group) {
        if (!group.ownerId().equals(callerId)) {
            throw new ForbiddenException(OWNER_ONLY);
        }
    }

    private GroupDetailDTO detail(UUID callerId, UUID groupId) {
        Group group = groups.findGroup(groupId).orElseThrow(() -> new NotFoundException(GROUP_NOT_FOUND));
        List<GroupMember> members = groups.findMembers(groupId);
        GroupMember mine = members.stream().filter(m -> m.userId().equals(callerId)).findFirst()
                .orElseThrow(() -> new NotFoundException(GROUP_NOT_FOUND));
        Map<UUID, UserSummaryDTO> summaries = summaries(callerId, members.stream().map(GroupMember::userId).toList());
        List<GroupMemberDTO> memberDTOs = members.stream()
                .filter(m -> summaries.containsKey(m.userId()))
                .map(m -> GroupMapper.toMemberDTO(m, summaries.get(m.userId())))
                .toList();
        List<UUID> activeIds = members.stream().filter(GroupMember::isActive).map(GroupMember::userId).toList();
        ContentFilter effective = contentFilter.effectiveFor(activeIds, group.cap());
        return new GroupDetailDTO(group.id(), group.name(), group.emoji(), group.color(), group.ownerId(), mine.role(),
                mine.status(), memberDTOs, GroupMapper.toCapDTO(group.cap()), effective.toDTO(),
                recentSessions(callerId, groupId));
    }

    private List<SessionSummaryDTO> recentSessions(UUID callerId, UUID groupId) {
        List<Session> recent = sessions.listFor(callerId, groupId, null, null, RECENT_SESSIONS);
        Map<Integer, MovieSummaryDTO> movies = movieSummaries.of(recent.stream()
                .map(Session::pickedMovieId).filter(Objects::nonNull).toList());
        Instant now = clock.instant();
        return recent.stream()
                .map(s -> GroupMapper.toSummaryDTO(s, s.pickedMovieId() == null ? null : movies.get(s.pickedMovieId()), now))
                .toList();
    }

    private Map<UUID, UserSummaryDTO> summaries(UUID viewer, Collection<UUID> ids) {
        return users.summaries(viewer, List.copyOf(ids)).stream()
                .collect(Collectors.toMap(UserSummaryDTO::id, Function.identity(), (a, b) -> a));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

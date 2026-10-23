import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import {
  MAX_GROUP_MEMBERS,
  useAcceptGroupInvite,
  useAddMembers,
  useDeclineGroupInvite,
  useDeleteGroup,
  useGroup,
  useLeaveGroup,
  useRemoveMember,
} from '../../api/groups';
import { useAuth } from '../../auth/useAuth';
import { BackButton } from '../../components/BackButton';
import { FilterBanner } from '../../components/comfort/FilterBanner';
import { FriendPicker } from '../../components/groups/FriendPicker';
import { GroupSettingsSheet } from '../../components/groups/GroupSettingsSheet';
import { groupColor, groupEmoji } from '../../components/groups/groupStyle';
import { useJoinSession } from '../../api/sessions';
import { LiveSessionCard } from '../../components/sessions/LiveSessionCard';
import { SessionHistorySection } from '../../components/sessions/SessionHub';
import { UserRow } from '../../components/social/UserRow';
import {
  Body,
  Button,
  Card,
  ConfirmSheet,
  EmptyState,
  ErrorState,
  Heading,
  Label,
  Screen,
  Sheet,
  Skeleton,
  errorMessage,
} from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import { personName, pluralize } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { GroupDetailDTO, GroupMemberDTO } from '../../types/api';

function memberSubtitle(m: GroupMemberDTO): string | undefined {
  if (m.status === 'INVITED') return 'Invited';
  return m.role === 'OWNER' ? 'Owner' : undefined;
}

function InviteMoreSheet({ group, visible, onClose }: { group: GroupDetailDTO; visible: boolean; onClose: () => void }) {
  const add = useAddMembers();
  const [selected, setSelected] = useState<string[]>([]);
  // Counts, not members.length: the list leaves out people blocked either way with the viewer.
  const room = Math.max(0, MAX_GROUP_MEMBERS - group.memberCount - group.invitedCount);
  const close = () => {
    setSelected([]);
    add.reset();
    onClose();
  };
  return (
    <Sheet visible={visible} onClose={close} title="Invite friends">
      <View className="gap-4">
        {room === 0 ? (
          <Body muted>This group is full ({MAX_GROUP_MEMBERS} people).</Body>
        ) : (
          <FriendPicker
            selected={selected}
            onChange={setSelected}
            max={room}
            excludeIds={group.members.map((m) => m.user.id)}
          />
        )}
        {add.isError ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {errorMessage(add.error)}
          </Body>
        ) : null}
        <Button
          title={selected.length ? `Invite ${pluralize(selected.length, 'friend')}` : 'Invite'}
          disabled={selected.length === 0}
          loading={add.isPending}
          onPress={() => add.mutate({ groupId: group.id, userIds: selected }, { onSuccess: close })}
        />
      </View>
    </Sheet>
  );
}

function InvitedView({ group }: { group: GroupDetailDTO }) {
  const router = useRouter();
  const accept = useAcceptGroupInvite();
  const decline = useDeclineGroupInvite();
  const failure = accept.isError ? accept.error : decline.isError ? decline.error : null;
  return (
    <Card testID="group-invite-card" className="gap-3">
      <Body>You’ve been invited to join {group.name}.</Body>
      {failure ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {errorMessage(failure)}
        </Body>
      ) : null}
      <View className="flex-row gap-3">
        <Button
          className="flex-1"
          title="Accept"
          accessibilityLabel="Accept invite"
          loading={accept.isPending}
          disabled={decline.isPending}
          onPress={() => accept.mutate(group.id)}
        />
        <Button
          className="flex-1"
          variant="secondary"
          title="Decline"
          accessibilityLabel="Decline invite"
          loading={decline.isPending}
          disabled={accept.isPending}
          onPress={() => decline.mutate(group.id, { onSuccess: () => router.replace('/groups') })}
        />
      </View>
    </Card>
  );
}

/** Open sessions of the group the caller isn't in yet (e.g. started before they joined). */
function JoinableSessions({ group, onSessionClosed }: { group: GroupDetailDTO; onSessionClosed: () => void }) {
  const router = useRouter();
  const join = useJoinSession();
  return (
    <View testID="joinable-sessions" className="gap-2">
      <Label accessibilityRole="header">Happening now</Label>
      {join.isError ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {errorMessage(join.error)}
        </Body>
      ) : null}
      {group.joinableSessions.map((s) => (
        <LiveSessionCard
          key={s.id}
          session={s}
          onPress={() => router.push(`/sessions/${s.id}`)}
          onExpire={onSessionClosed}
        >
          <Button
            size="sm"
            title="Join"
            accessibilityLabel="Join this session"
            loading={join.isPending && join.variables === s.id}
            onPress={() => join.mutate(s.id, { onSuccess: () => router.push(`/sessions/${s.id}`) })}
          />
        </LiveSessionCard>
      ))}
    </View>
  );
}

type Confirm = { kind: 'remove'; member: GroupMemberDTO } | { kind: 'leave' } | { kind: 'delete' } | null;

function GroupDetail({ group, onSessionClosed }: { group: GroupDetailDTO; onSessionClosed: () => void }) {
  const router = useRouter();
  const { colors } = useTheme();
  const { user } = useAuth();
  const remove = useRemoveMember();
  const leave = useLeaveGroup();
  const del = useDeleteGroup();
  const [confirm, setConfirm] = useState<Confirm>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [inviting, setInviting] = useState(false);

  const isOwner = group.role === 'OWNER';
  const invited = group.memberStatus === 'INVITED';
  const color = groupColor(group.color);
  const activeCount = group.memberCount;

  const closeConfirm = () => {
    remove.reset();
    leave.reset();
    del.reset();
    setConfirm(null);
  };
  const runConfirm = () => {
    if (!confirm) return;
    if (confirm.kind === 'remove') {
      remove.mutate({ groupId: group.id, userId: confirm.member.user.id }, { onSuccess: closeConfirm });
    } else if (confirm.kind === 'leave' && user) {
      leave.mutate({ groupId: group.id, userId: user.id }, { onSuccess: () => router.replace('/groups') });
    } else if (confirm.kind === 'delete') {
      del.mutate(group.id, { onSuccess: () => router.replace('/groups') });
    }
  };
  const confirmCopy =
    confirm?.kind === 'remove'
      ? {
          title: `Remove ${personName(confirm.member.user)}?`,
          message: 'They’ll lose access to this group and its movie nights.',
          label: 'Remove',
        }
      : confirm?.kind === 'leave'
        ? {
            title: `Leave ${group.name}?`,
            message: isOwner
              ? 'Ownership passes to the longest-standing member. You’ll need a new invite to come back.'
              : 'You’ll need a new invite to come back.',
            label: 'Leave group',
          }
        : { title: `Delete ${group.name}?`, message: 'This removes the group for everyone.', label: 'Delete group' };

  return (
    <View className="gap-6 pb-12">
      <View className="flex-row items-center gap-4">
        <View
          className="h-16 w-16 items-center justify-center rounded-2xl"
          style={{ backgroundColor: `${color}33`, borderColor: color, borderWidth: 2 }}
        >
          <Text className="text-4xl">{groupEmoji(group.emoji)}</Text>
        </View>
        <View className="flex-1">
          <Heading size="md" numberOfLines={2}>
            {group.name}
          </Heading>
          <Body muted className="text-sm">
            {pluralize(activeCount, 'member')}
          </Body>
        </View>
        {isOwner ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Group settings"
            hitSlop={8}
            onPress={() => setSettingsOpen(true)}
            className="h-10 w-10 items-center justify-center rounded-xl bg-raised active:opacity-70"
          >
            <Ionicons name="settings-outline" size={20} color={colors.text} />
          </Pressable>
        ) : null}
      </View>

      {invited ? (
        <InvitedView group={group} />
      ) : (
        <>
          <FilterBanner filter={group.effectiveFilter} />
          <Button
            title="Start session"
            icon={<Ionicons name="play" size={18} color={colors.onAccent} />}
            onPress={() => router.push(`/sessions/new?groupId=${encodeURIComponent(group.id)}`)}
          />
        </>
      )}

      <View className="gap-2">
        <View className="flex-row items-center justify-between">
          <Label accessibilityRole="header">Members</Label>
          {!invited ? (
            <Button size="sm" variant="ghost" title="Invite friends" onPress={() => setInviting(true)} />
          ) : null}
        </View>
        <Card className="gap-1 py-2">
          {group.members.map((m) => (
            <UserRow
              key={m.user.id}
              user={m.user}
              subtitle={memberSubtitle(m)}
              onPress={() => router.push(`/user/${m.user.id}`)}
            >
              {isOwner && m.user.id !== user?.id ? (
                <Button
                  size="sm"
                  variant="secondary"
                  title="Remove"
                  accessibilityLabel={`Remove ${personName(m.user)}`}
                  onPress={() => setConfirm({ kind: 'remove', member: m })}
                />
              ) : null}
            </UserRow>
          ))}
        </Card>
      </View>

      {!invited && group.joinableSessions.length ? <JoinableSessions group={group} onSessionClosed={onSessionClosed} /> : null}
      {!invited ? <SessionHistorySection groupId={group.id} title="Movie nights" /> : null}

      {!invited ? (
        <Button title="Leave group" variant="secondary" onPress={() => setConfirm({ kind: 'leave' })} />
      ) : null}

      <ConfirmSheet
        visible={confirm !== null}
        title={confirmCopy.title}
        message={confirmCopy.message}
        confirmLabel={confirmCopy.label}
        danger
        loading={remove.isPending || leave.isPending || del.isPending}
        error={remove.error ?? leave.error ?? del.error}
        onConfirm={runConfirm}
        onClose={closeConfirm}
      />
      {isOwner ? (
        <GroupSettingsSheet
          group={group}
          visible={settingsOpen}
          onClose={() => setSettingsOpen(false)}
          onDelete={() => {
            setSettingsOpen(false);
            setConfirm({ kind: 'delete' });
          }}
        />
      ) : null}
      {!invited ? <InviteMoreSheet group={group} visible={inviting} onClose={() => setInviting(false)} /> : null}
    </View>
  );
}

/** A saved group: members, the effective filter, start a session, history; invite / settings / leave. */
export default function GroupScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const group = useGroup(id);

  let content;
  if (group.isPending) {
    content = (
      <View testID="group-loading" className="gap-4">
        <Skeleton className="h-16 w-2/3" />
        <Skeleton className="h-12 w-full" />
        <Skeleton className="h-40 w-full" />
      </View>
    );
  } else if (group.isError) {
    content =
      group.error instanceof ApiError && group.error.status === 404 ? (
        <EmptyState icon="albums-outline" title="Group not found" message="It may have been deleted, or you’re no longer in it." />
      ) : (
        <ErrorState
          title="Couldn't load this group"
          error={group.error}
          onRetry={() => void group.refetch()}
          retrying={group.isFetching}
        />
      );
  } else {
    content = <GroupDetail group={group.data} onSessionClosed={() => void group.refetch()} />;
  }

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/groups" />
      </View>
      {content}
    </Screen>
  );
}

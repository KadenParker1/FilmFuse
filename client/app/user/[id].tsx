import { useState } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMe } from '../../api/me';
import {
  useAcceptRequest,
  useBlock,
  useCancelRequest,
  useDeclineRequest,
  useSendFriendRequest,
  useSetCloseFriend,
  useUnblock,
  useUnfriend,
} from '../../api/social';
import { usePublicProfile } from '../../api/users';
import { useAuth } from '../../auth/useAuth';
import { BackButton } from '../../components/BackButton';
import { UserRatingsSection } from '../../components/feed/RatingsSections';
import { ReportForm } from '../../components/moderation/ReportSheet';
import { SaveLinkAndSignIn } from '../../components/SaveLinkAndSignIn';
import { Avatar, Body, Button, EmptyState, errorMessage, ErrorState, Heading, Sheet, Skeleton, Toggle } from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import { personName, pluralize } from '../../lib/format';
import { profilePath } from '../../lib/pendingLinkPath';
import { useTheme } from '../../theme/useTheme';
import type { PublicProfileDTO } from '../../types/api';

const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

type SheetMode = 'menu' | 'friends' | 'confirm-cancel' | 'confirm-unfriend' | 'confirm-block' | 'report';

function ProfileSkeleton() {
  return (
    <View testID="user-profile-loading" className="items-center gap-3 pt-4">
      <Skeleton className="h-28 w-28 rounded-full" />
      <Skeleton className="h-7 w-48" />
      <Skeleton className="h-4 w-28" />
      <Skeleton className="mt-4 h-12 w-full max-w-sm rounded-full" />
    </View>
  );
}

function ActionError({ error }: { error: unknown }) {
  return (
    <Text testID="profile-action-error" accessibilityRole="alert" className="text-center font-body text-sm text-danger">
      {errorMessage(error)}
    </Text>
  );
}

/** Close-friend switch inside the "Friends ✓" sheet. The profile carries the flag; toggling is optimistic. */
function CloseFriendSwitch({ profile }: { profile: PublicProfileDTO }) {
  const { colors } = useTheme();
  const setClose = useSetCloseFriend();
  const closeFriend = profile.closeFriend;

  return (
    <View className="gap-1">
      <View className="flex-row items-center justify-between gap-4 rounded-2xl bg-raised px-4 py-3">
        <View className="flex-1 flex-row items-center gap-2">
          <Ionicons name="star" size={18} color={colors.accent} />
          <Body>Close friend</Body>
        </View>
        <Toggle
          accessibilityLabel="Close friend"
          accessibilityState={{ checked: closeFriend, disabled: setClose.isPending }}
          value={closeFriend}
          disabled={setClose.isPending}
          onValueChange={(next) => setClose.mutate({ userId: profile.id, closeFriend: next })}
        />
      </View>
      <Body muted className="text-sm">
        Close friends count for more when FilmFuse picks movies for your groups.
      </Body>
      {setClose.isError ? <ActionError error={setClose.error} /> : null}
    </View>
  );
}

function ProfileView({ profile }: { profile: PublicProfileDTO }) {
  const router = useRouter();
  const { colors } = useTheme();
  const me = useMe();
  const [mode, setMode] = useState<SheetMode | null>(null);

  const send = useSendFriendRequest();
  const accept = useAcceptRequest();
  const decline = useDeclineRequest();
  const cancel = useCancelRequest();
  const unfriend = useUnfriend();
  const block = useBlock();
  const unblock = useUnblock();

  const name = personName(profile);
  const state = profile.friendship;
  const isSelf = me.data?.id === profile.id;
  const showOverflow = !isSelf && (state === 'NONE' || state === 'REQUEST_SENT' || state === 'REQUEST_RECEIVED');
  const inlineFailure = [send, accept, decline, unblock].find((m) => m.isError)?.error;
  const busy = [send, accept, decline, cancel, unfriend, block, unblock].some((m) => m.isPending);

  const closeSheet = () => {
    if (cancel.isPending || unfriend.isPending || block.isPending) return;
    cancel.reset();
    unfriend.reset();
    block.reset();
    setMode(null);
  };
  const done = { onSuccess: () => setMode(null) };

  let actions;
  if (isSelf) {
    actions = <Button title="Edit your profile" variant="secondary" onPress={() => router.push('/profile/edit')} />;
  } else {
    switch (state) {
      case 'NONE':
        actions = (
          <Button
            title="Add friend"
            loading={send.isPending}
            icon={<Ionicons name="person-add-outline" size={18} color={colors.onAccent} />}
            onPress={() => send.mutate(profile.id)}
          />
        );
        break;
      case 'REQUEST_SENT':
        actions = (
          <Button
            title="Requested"
            variant="secondary"
            accessibilityHint="Cancels your friend request"
            icon={<Ionicons name="time-outline" size={18} color={colors.text} />}
            onPress={() => setMode('confirm-cancel')}
          />
        );
        break;
      case 'REQUEST_RECEIVED':
        actions = (
          <View className="gap-3">
            <Body muted className="text-center">
              {name} sent you a friend request.
            </Body>
            <View className="flex-row gap-3">
              <Button
                title="Accept"
                className="flex-1"
                loading={accept.isPending}
                disabled={busy && !accept.isPending}
                onPress={() => accept.mutate(profile.id)}
              />
              <Button
                title="Decline"
                variant="secondary"
                className="flex-1"
                loading={decline.isPending}
                disabled={busy && !decline.isPending}
                onPress={() => decline.mutate(profile.id)}
              />
            </View>
          </View>
        );
        break;
      case 'FRIENDS':
        actions = (
          <View className="gap-3">
            <Button
              title={`Movie night with ${name}`}
              icon={<Ionicons name="film-outline" size={18} color={colors.onAccent} />}
              onPress={() => router.push(`/sessions/new?memberIds=${encodeURIComponent(profile.id)}`)}
            />
            <Button
              title="Friends ✓"
              variant="secondary"
              accessibilityLabel="Friends"
              accessibilityHint="Opens friendship options"
              onPress={() => setMode('friends')}
            />
          </View>
        );
        break;
      case 'BLOCKED':
        actions = (
          <View className="gap-3">
            <Body muted className="text-center">
              You&apos;ve blocked {name}. They can&apos;t find you or send you requests.
            </Body>
            <Button
              title="Unblock"
              variant="secondary"
              loading={unblock.isPending}
              onPress={() => unblock.mutate(profile.id)}
            />
          </View>
        );
        break;
    }
  }

  let sheetTitle: string | undefined;
  let sheetBody;
  switch (mode) {
    case 'menu':
      sheetTitle = name;
      sheetBody = (
        <View className="gap-3">
          <Button title={`Block ${name}`} variant="danger" onPress={() => setMode('confirm-block')} />
          <Button title={`Report ${name}`} variant="secondary" onPress={() => setMode('report')} />
          <Button title="Cancel" variant="ghost" onPress={closeSheet} />
        </View>
      );
      break;
    case 'friends':
      sheetTitle = `You and ${name}`;
      sheetBody = (
        <View className="gap-4">
          <CloseFriendSwitch profile={profile} />
          <Button title="Unfriend" variant="secondary" onPress={() => setMode('confirm-unfriend')} />
          <Button title="Block" variant="danger" onPress={() => setMode('confirm-block')} />
          <Button title="Report" variant="secondary" accessibilityLabel={`Report ${name}`} onPress={() => setMode('report')} />
          <Button title="Done" variant="ghost" onPress={closeSheet} />
        </View>
      );
      break;
    case 'confirm-cancel':
      sheetTitle = 'Cancel friend request?';
      sheetBody = (
        <View className="gap-3">
          <Body muted>{name} won&apos;t see your request anymore. You can send another one later.</Body>
          {cancel.isError ? <ActionError error={cancel.error} /> : null}
          <Button
            title="Cancel request"
            variant="danger"
            loading={cancel.isPending}
            onPress={() => cancel.mutate(profile.id, done)}
          />
          <Button title="Keep request" variant="ghost" disabled={cancel.isPending} onPress={closeSheet} />
        </View>
      );
      break;
    case 'confirm-unfriend':
      sheetTitle = `Unfriend ${name}?`;
      sheetBody = (
        <View className="gap-3">
          <Body muted>
            You&apos;ll stop seeing each other&apos;s friends-only ratings. You can send a new request later.
          </Body>
          {unfriend.isError ? <ActionError error={unfriend.error} /> : null}
          <Button
            title="Unfriend"
            variant="danger"
            loading={unfriend.isPending}
            onPress={() => unfriend.mutate(profile.id, done)}
          />
          <Button title="Cancel" variant="ghost" disabled={unfriend.isPending} onPress={closeSheet} />
        </View>
      );
      break;
    case 'report':
      // The form brings its own heading and thank-you.
      sheetBody = <ReportForm targetType="USER" targetId={profile.id} onDone={closeSheet} />;
      break;
    case 'confirm-block':
      sheetTitle = `Block ${name}?`;
      sheetBody = (
        <View className="gap-3">
          <Body muted>
            They won&apos;t be able to find you, see your profile or send you requests. Any friendship or pending
            requests between you are removed. They aren&apos;t told.
          </Body>
          {block.isError ? <ActionError error={block.error} /> : null}
          <Button
            title="Block"
            variant="danger"
            loading={block.isPending}
            onPress={() => block.mutate(profile.id, done)}
          />
          <Button title="Cancel" variant="ghost" disabled={block.isPending} onPress={closeSheet} />
        </View>
      );
      break;
  }

  return (
    <View className="w-full max-w-xl gap-6 self-center">
      <View className="flex-row items-center justify-between">
        <BackButton fallback="/" />
        {showOverflow ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="More options"
            hitSlop={8}
            onPress={() => setMode('menu')}
            className="h-10 w-10 items-center justify-center rounded-xl bg-raised active:opacity-70"
          >
            <Ionicons name="ellipsis-horizontal" size={20} color={colors.text} />
          </Pressable>
        ) : null}
      </View>

      <View className="items-center gap-2">
        <Avatar
          uri={profile.profileImageUrl}
          displayName={profile.displayName}
          username={profile.username}
          size={112}
        />
        <Heading size="md" className="mt-2 text-center">
          {name}
        </Heading>
        {profile.displayName?.trim() && profile.username ? <Body muted>@{profile.username}</Body> : null}
        {profile.bio ? (
          <Body testID="user-bio" className="max-w-md text-center">
            {profile.bio}
          </Body>
        ) : null}
        <View className="mt-2 flex-row gap-6">
          <Body testID="friend-count" muted className="text-sm">
            {pluralize(profile.friendCount, 'friend')}
          </Body>
          {!isSelf ? (
            <Body testID="mutual-count" muted className="text-sm">
              {pluralize(profile.mutualFriendCount, 'mutual friend')}
            </Body>
          ) : null}
        </View>
      </View>

      <View className="w-full max-w-sm gap-3 self-center">
        {actions}
        {inlineFailure ? <ActionError error={inlineFailure} /> : null}
      </View>

      {/* Blocked people's ratings aren't shown (the server answers 404). */}
      {state !== 'BLOCKED' ? <UserRatingsSection userId={profile.id} name={name} isSelf={isSelf} /> : null}

      <Sheet visible={mode !== null} onClose={closeSheet} title={sheetTitle}>
        {sheetBody}
      </Sheet>
    </View>
  );
}

/**
 * `/user/<id>` — reachable signed out too (email links): then it remembers the profile and sends
 * you to sign in; the tabs layout reopens it afterwards.
 */
export default function UserProfileRoute() {
  const { session } = useAuth();
  const id = first(useLocalSearchParams<{ id: string }>().id);
  if (!session && id) return <SaveLinkAndSignIn path={profilePath(id)} label="Sign in to see this profile" />;
  return <UserProfileScreen id={id} />;
}

/** Someone's public profile with the friendship actions that fit your relationship. */
function UserProfileScreen({ id }: { id: string | undefined }) {
  const insets = useSafeAreaInsets();
  const profile = usePublicProfile(id);
  const notFound = !id || (profile.error instanceof ApiError && profile.error.status === 404);

  let content;
  if (notFound) {
    content = (
      <View className="flex-1">
        <BackButton fallback="/" />
        <EmptyState
          icon="person-remove-outline"
          title="This profile isn't available"
          message="The account may have been deleted, or the link is wrong."
        />
      </View>
    );
  } else if (profile.isError) {
    content = (
      <View className="flex-1">
        <BackButton fallback="/" />
        <ErrorState
          title="Couldn't load this profile"
          error={profile.error}
          onRetry={() => void profile.refetch()}
          retrying={profile.isFetching}
        />
      </View>
    );
  } else if (profile.isPending) {
    content = <ProfileSkeleton />;
  } else {
    content = <ProfileView profile={profile.data} />;
  }

  return (
    <View className="flex-1 bg-bg">
      <ScrollView
        contentContainerClassName="flex-grow px-5"
        contentContainerStyle={{ paddingTop: insets.top + 12, paddingBottom: insets.bottom + 32 }}
      >
        {content}
      </ScrollView>
    </View>
  );
}

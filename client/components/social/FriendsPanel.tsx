import type { ReactNode } from 'react';
import { Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { flattenPages } from '../../api/movies';
import {
  useAcceptRequest,
  useCancelRequest,
  useDeclineRequest,
  useFriendRequests,
  useFriends,
  useSendFriendRequest,
  useSetCloseFriend,
  useSuggestions,
} from '../../api/social';
import { personName, pluralize } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import { Body, Button, Card, ErrorState, Label, errorMessage } from '../ui';
import { CloseFriendStar } from './CloseFriendStar';
import { InviteCard } from './InviteCard';
import { UserRow, UserRowSkeletons } from './UserRow';

function Section({ title, testID, children }: { title: string; testID: string; children: ReactNode }) {
  return (
    <View testID={testID} className="gap-2">
      <Label accessibilityRole="header">{title}</Label>
      <Card className="gap-1 py-2">{children}</Card>
    </View>
  );
}

function InlineError({ error }: { error: unknown }) {
  return (
    <Text accessibilityRole="alert" className="py-1 font-body text-sm text-danger">
      {errorMessage(error)}
    </Text>
  );
}

const useOpenProfile = () => {
  const router = useRouter();
  return (id: string) => router.push(`/user/${id}`);
};

function IncomingRequests() {
  const openProfile = useOpenProfile();
  const incoming = useFriendRequests('INCOMING');
  const accept = useAcceptRequest();
  const decline = useDeclineRequest();

  if (incoming.isPending) {
    return (
      <Section title="Friend requests" testID="section-incoming">
        <UserRowSkeletons count={1} testID="incoming-loading" />
      </Section>
    );
  }
  if (incoming.isError) {
    return (
      <Section title="Friend requests" testID="section-incoming">
        <ErrorState
          compact
          title="Couldn't load requests"
          error={incoming.error}
          onRetry={() => void incoming.refetch()}
          retrying={incoming.isFetching}
        />
      </Section>
    );
  }
  if (incoming.data.length === 0) return null;

  const failure = accept.isError ? accept.error : decline.isError ? decline.error : null;
  return (
    <Section title={`Friend requests · ${incoming.data.length}`} testID="section-incoming">
      {failure ? <InlineError error={failure} /> : null}
      {incoming.data.map(({ user }) => {
        const name = personName(user);
        const busy =
          (accept.isPending && accept.variables === user.id) || (decline.isPending && decline.variables === user.id);
        return (
          <UserRow key={user.id} user={user} subtitle="Wants to be friends" onPress={() => openProfile(user.id)}>
            <Button
              size="sm"
              title="Accept"
              accessibilityLabel={`Accept friend request from ${name}`}
              loading={accept.isPending && accept.variables === user.id}
              disabled={busy}
              onPress={() => accept.mutate(user.id)}
            />
            <Button
              size="sm"
              variant="secondary"
              title="Decline"
              accessibilityLabel={`Decline friend request from ${name}`}
              loading={decline.isPending && decline.variables === user.id}
              disabled={busy}
              onPress={() => decline.mutate(user.id)}
            />
          </UserRow>
        );
      })}
    </Section>
  );
}

function Suggestions() {
  const { colors } = useTheme();
  const openProfile = useOpenProfile();
  const suggestions = useSuggestions();
  const send = useSendFriendRequest();

  if (suggestions.isPending) {
    return (
      <Section title="People you may know" testID="section-suggestions">
        <UserRowSkeletons count={2} testID="suggestions-loading" />
      </Section>
    );
  }
  // Suggestions are a nice-to-have: on failure (or none) the section simply isn't shown.
  if (suggestions.isError || suggestions.data.length === 0) return null;

  return (
    <Section title="People you may know" testID="section-suggestions">
      {send.isError ? <InlineError error={send.error} /> : null}
      {suggestions.data.map(({ user, mutualFriendCount }) => (
        <UserRow
          key={user.id}
          user={user}
          subtitle={mutualFriendCount > 0 ? pluralize(mutualFriendCount, 'mutual friend') : undefined}
          onPress={() => openProfile(user.id)}
        >
          <Button
            size="sm"
            title="Add"
            accessibilityLabel={`Add ${personName(user)} as a friend`}
            loading={send.isPending && send.variables === user.id}
            icon={<Ionicons name="person-add-outline" size={14} color={colors.onAccent} />}
            onPress={() => send.mutate(user.id)}
          />
        </UserRow>
      ))}
    </Section>
  );
}

function FriendsList() {
  const openProfile = useOpenProfile();
  const friends = useFriends();
  const setClose = useSetCloseFriend();
  const items = flattenPages(friends.data);

  let body: ReactNode;
  if (friends.isPending) {
    body = <UserRowSkeletons count={3} testID="friends-loading" />;
  } else if (friends.isError && items.length === 0) {
    body = (
      <ErrorState
        compact
        title="Couldn't load your friends"
        error={friends.error}
        onRetry={() => void friends.refetch()}
        retrying={friends.isFetching}
      />
    );
  } else if (items.length === 0) {
    body = (
      <View testID="friends-empty" className="items-center gap-1 py-4">
        <Body className="text-center">No friends yet</Body>
        <Body muted className="text-center text-sm">
          Search for people above, or share an invite link below.
        </Body>
      </View>
    );
  } else {
    body = (
      <>
        {setClose.isError ? <InlineError error={setClose.error} /> : null}
        {items.map((f) => (
          <UserRow
            key={f.id}
            user={f}
            subtitle={f.closeFriend ? 'Close friend' : undefined}
            onPress={() => openProfile(f.id)}
          >
            <CloseFriendStar
              name={personName(f)}
              closeFriend={f.closeFriend}
              onToggle={(closeFriend) => setClose.mutate({ userId: f.id, closeFriend })}
            />
          </UserRow>
        ))}
        {friends.isError ? <InlineError error={friends.error} /> : null}
        {friends.hasNextPage ? (
          <Button
            variant="ghost"
            title="Show more friends"
            loading={friends.isFetchingNextPage}
            onPress={() => void friends.fetchNextPage()}
          />
        ) : null}
      </>
    );
  }

  return (
    <Section title="Your friends" testID="section-friends">
      {body}
    </Section>
  );
}

function OutgoingRequests() {
  const openProfile = useOpenProfile();
  const outgoing = useFriendRequests('OUTGOING');
  const cancel = useCancelRequest();

  // Secondary info: nothing while loading, on failure, or when there are none.
  if (!outgoing.data || outgoing.data.length === 0) return null;

  return (
    <Section title="Sent requests" testID="section-outgoing">
      {cancel.isError ? <InlineError error={cancel.error} /> : null}
      {outgoing.data.map(({ user }) => (
        <UserRow key={user.id} user={user} subtitle="Waiting for them to accept" onPress={() => openProfile(user.id)}>
          <Button
            size="sm"
            variant="secondary"
            title="Cancel"
            accessibilityLabel={`Cancel friend request to ${personName(user)}`}
            loading={cancel.isPending && cancel.variables === user.id}
            onPress={() => cancel.mutate(user.id)}
          />
        </UserRow>
      ))}
    </Section>
  );
}

/** The Social tab's Friends segment: requests, suggestions, friends, sent requests, invite link. */
export function FriendsPanel() {
  return (
    <View testID="friends-panel" className="gap-6 pb-28">
      <IncomingRequests />
      <Suggestions />
      <FriendsList />
      <OutgoingRequests />
      <InviteCard />
    </View>
  );
}

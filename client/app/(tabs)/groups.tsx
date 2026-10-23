import { Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useAcceptGroupInvite, useDeclineGroupInvite, useGroups } from '../../api/groups';
import { GroupCard } from '../../components/groups/GroupCard';
import { PendingRatingsBanner } from '../../components/rating/PendingRatingsBanner';
import { LiveNowSection, SessionHistorySection } from '../../components/sessions/SessionHub';
import { Body, Button, EmptyState, ErrorState, Heading, Label, Screen, Skeleton, errorMessage } from '../../components/ui';
import { useTheme } from '../../theme/useTheme';

/** The big cobalt "Tonight" call to action: an ad-hoc session with whichever friends are around. */
function TonightCta({ onPress }: { onPress: () => void }) {
  const { colors } = useTheme();
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel="Tonight: start a movie night with friends"
      onPress={onPress}
      className="flex-row items-center gap-4 rounded-[22px] bg-accent px-5 py-5 active:opacity-90"
    >
      <View className="h-12 w-12 items-center justify-center rounded-2xl" style={{ backgroundColor: colors.accent2 }}>
        <Ionicons name="sparkles" size={24} color={colors.onAccent2} />
      </View>
      <View className="flex-1">
        <Text className="font-display-bold text-2xl tracking-tight text-on-accent">Tonight</Text>
        <Text className="font-body text-sm text-on-accent">Pick friends, get picks everyone will like.</Text>
      </View>
      <Ionicons name="arrow-forward" size={24} color={colors.onAccent} />
    </Pressable>
  );
}

export default function GroupsScreen() {
  const router = useRouter();
  const { colors } = useTheme();
  const groups = useGroups();
  const accept = useAcceptGroupInvite();
  const decline = useDeclineGroupInvite();

  const openGroup = (id: string) => router.push(`/groups/${id}`);
  const createButton = (
    <Button
      title="Create a group"
      variant="secondary"
      icon={<Ionicons name="add" size={18} color={colors.text} />}
      onPress={() => router.push('/groups/new')}
    />
  );

  let content;
  if (groups.isPending) {
    content = (
      <View testID="groups-loading" className="gap-3">
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-20 w-full" />
      </View>
    );
  } else if (groups.isError) {
    content = (
      <ErrorState
        title="Couldn't load your groups"
        error={groups.error}
        onRetry={() => void groups.refetch()}
        retrying={groups.isFetching}
      />
    );
  } else {
    const invites = groups.data.filter((g) => g.memberStatus === 'INVITED');
    const active = groups.data.filter((g) => g.memberStatus === 'ACTIVE');
    const failure = accept.isError ? accept.error : decline.isError ? decline.error : null;
    content = (
      <View className="gap-6">
        {invites.length ? (
          <View testID="group-invites" className="gap-3">
            <Label accessibilityRole="header">Invites · {invites.length}</Label>
            {failure ? (
              <Body accessibilityRole="alert" className="text-sm text-danger">
                {errorMessage(failure)}
              </Body>
            ) : null}
            {invites.map((g) => {
              const busy =
                (accept.isPending && accept.variables === g.id) || (decline.isPending && decline.variables === g.id);
              return (
                <GroupCard key={g.id} group={g} onPress={() => openGroup(g.id)}>
                  <Button
                    size="sm"
                    title="Accept"
                    accessibilityLabel={`Accept invite to ${g.name}`}
                    loading={accept.isPending && accept.variables === g.id}
                    disabled={busy}
                    onPress={() => accept.mutate(g.id, { onSuccess: () => openGroup(g.id) })}
                  />
                  <Button
                    size="sm"
                    variant="secondary"
                    title="Decline"
                    accessibilityLabel={`Decline invite to ${g.name}`}
                    loading={decline.isPending && decline.variables === g.id}
                    disabled={busy}
                    onPress={() => decline.mutate(g.id)}
                  />
                </GroupCard>
              );
            })}
          </View>
        ) : null}

        {active.length ? (
          <View testID="group-list" className="gap-3">
            <Label accessibilityRole="header">Your groups</Label>
            {active.map((g) => (
              <GroupCard key={g.id} group={g} onPress={() => openGroup(g.id)} />
            ))}
            {createButton}
          </View>
        ) : (
          <View>
            <EmptyState
              icon="albums-outline"
              title="No groups yet"
              message="Save the people you watch with — family, flatmates, film club — and start a movie night in one tap."
            />
            {createButton}
          </View>
        )}
      </View>
    );
  }

  return (
    <Screen edges={['top']} scroll>
      <View className="gap-6 pb-28">
        <Heading>Groups</Heading>
        <TonightCta onPress={() => router.push('/sessions/new')} />
        <LiveNowSection />
        <PendingRatingsBanner />
        {content}
        <SessionHistorySection title="Past movie nights" />
      </View>
    </Screen>
  );
}

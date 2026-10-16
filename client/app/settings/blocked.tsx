import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { useBlocks, useUnblock } from '../../api/social';
import { BackButton } from '../../components/BackButton';
import { UserRow, UserRowSkeletons } from '../../components/social/UserRow';
import { Body, Button, Card, EmptyState, ErrorState, Heading, Screen, errorMessage } from '../../components/ui';
import { personName } from '../../lib/format';

/** Settings → Blocked people: everyone you've blocked, each with Unblock. */
export default function BlockedPeopleScreen() {
  const router = useRouter();
  const blocks = useBlocks();
  const unblock = useUnblock();

  let content;
  if (blocks.isPending) {
    content = <UserRowSkeletons count={3} testID="blocked-loading" />;
  } else if (blocks.isError) {
    content = (
      <ErrorState
        title="Couldn't load blocked people"
        error={blocks.error}
        onRetry={() => void blocks.refetch()}
        retrying={blocks.isFetching}
      />
    );
  } else if (blocks.data.length === 0) {
    content = (
      <EmptyState
        icon="shield-checkmark-outline"
        title="No one blocked"
        message="People you block can't find you, see your profile or send you requests."
      />
    );
  } else {
    content = (
      <Card className="gap-1 py-2">
        {unblock.isError ? (
          <Body accessibilityRole="alert" className="py-1 text-sm text-velvet">
            {errorMessage(unblock.error)}
          </Body>
        ) : null}
        {blocks.data.map((user) => (
          <UserRow key={user.id} user={user} onPress={() => router.push(`/user/${user.id}`)}>
            <Button
              size="sm"
              variant="secondary"
              title="Unblock"
              accessibilityLabel={`Unblock ${personName(user)}`}
              loading={unblock.isPending && unblock.variables === user.id}
              onPress={() => unblock.mutate(user.id)}
            />
          </UserRow>
        ))}
      </Card>
    );
  }

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/profile" />
        <Heading size="md">Blocked people</Heading>
      </View>
      {content}
    </Screen>
  );
}

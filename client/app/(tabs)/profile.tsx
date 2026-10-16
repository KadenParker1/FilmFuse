import { View } from 'react-native';
import { useMe } from '../../api/me';
import { useAuth } from '../../auth/useAuth';
import { Body, Button, Card, EmptyState, Heading, Label, Screen } from '../../components/ui';

export default function ProfileScreen() {
  const { user, signOut } = useAuth();
  const me = useMe();
  const name = me.data?.displayName ?? me.data?.username ?? null;

  return (
    <Screen edges={['top']} scroll>
      <Heading>Profile</Heading>
      <Card className="mt-6 gap-3">
        <View className="gap-1">
          <Label>Signed in as</Label>
          <Heading size="sm">{name ?? 'New member'}</Heading>
          {me.data?.username ? <Body muted>@{me.data.username}</Body> : null}
          {user?.email ? <Body muted>{user.email}</Body> : null}
          {me.isError ? (
            <Body muted className="text-sm">
              Profile details unavailable — the server couldn&apos;t be reached.
            </Body>
          ) : null}
        </View>
        <Button title="Sign out" variant="danger" onPress={() => void signOut()} />
      </Card>
      <EmptyState
        icon="film-outline"
        title="Your taste, on display"
        message="Your reviews, taste persona and taste editor arrive in Phase 1b."
      />
    </Screen>
  );
}

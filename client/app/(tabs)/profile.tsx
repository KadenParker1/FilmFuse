import { useState } from 'react';
import { View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useMe } from '../../api/me';
import { useAuth } from '../../auth/useAuth';
import { DeleteAccountSheet } from '../../components/profile/DeleteAccountSheet';
import { Avatar, Body, Button, Card, EmptyState, ErrorState, Heading, Label, Screen, Skeleton } from '../../components/ui';
import { useTheme } from '../../theme/useTheme';

function ProfileHeader() {
  const router = useRouter();
  const { colors } = useTheme();
  const { user } = useAuth();
  const me = useMe();

  if (me.isPending) {
    return (
      <Card testID="profile-loading" className="mt-6 flex-row items-center gap-4">
        <Skeleton className="h-20 w-20 rounded-full" />
        <View className="flex-1 gap-2">
          <Skeleton className="h-6 w-2/3" />
          <Skeleton className="h-4 w-1/3" />
        </View>
      </Card>
    );
  }

  if (me.isError) {
    return (
      <Card className="mt-6">
        <ErrorState
          compact
          title="Profile unavailable"
          error={me.error}
          onRetry={() => void me.refetch()}
          retrying={me.isFetching}
        />
      </Card>
    );
  }

  const profile = me.data;
  const name = profile.displayName || profile.username || 'New member';

  return (
    <Card className="mt-6 gap-4">
      <View className="flex-row items-center gap-4">
        <Avatar uri={profile.profileImageUrl} displayName={profile.displayName} username={profile.username} size={80} />
        <View className="flex-1 gap-0.5">
          <Heading size="sm">{name}</Heading>
          {profile.username ? <Body muted>@{profile.username}</Body> : null}
          {user?.email ? <Body muted className="text-sm">{user.email}</Body> : null}
        </View>
      </View>
      {profile.bio ? <Body testID="profile-bio">{profile.bio}</Body> : null}
      <Button
        title="Edit profile"
        variant="secondary"
        icon={<Ionicons name="create-outline" size={18} color={colors.text} />}
        onPress={() => router.push('/profile/edit')}
      />
    </Card>
  );
}

export default function ProfileScreen() {
  const { signOut } = useAuth();
  const [deleting, setDeleting] = useState(false);

  return (
    <Screen edges={['top']} scroll>
      <Heading>Profile</Heading>
      <ProfileHeader />

      <EmptyState
        icon="film-outline"
        title="Your taste, on display"
        message="Your reviews, taste persona and taste editor arrive in Phase 1b."
      />

      <View className="gap-3 pb-28">
        <Label>Settings</Label>
        <Card className="gap-3">
          <Button title="Sign out" variant="secondary" onPress={() => void signOut()} />
          <Button title="Delete account" variant="danger" onPress={() => setDeleting(true)} />
        </Card>
      </View>
      <DeleteAccountSheet visible={deleting} onClose={() => setDeleting(false)} />
    </Screen>
  );
}

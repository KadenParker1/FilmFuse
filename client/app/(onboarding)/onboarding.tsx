import { useState } from 'react';
import { KeyboardAvoidingView, Platform, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useCompleteOnboarding, useMe, useUpdateMe } from '../../api/me';
import { normalizeUsername } from '../../api/usernames';
import { useAuth } from '../../auth/useAuth';
import { FullScreenLoader } from '../../components/FullScreenLoader';
import { ProfileBasicsForm, useUsernameCheck } from '../../components/profile/ProfileBasicsForm';
import { ProfilePhotoSection } from '../../components/profile/ProfilePhotoSection';
import { Body, Button, Card, ErrorState, Heading, Screen, errorMessage } from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import type { ProfileResponseDTO, UpdateProfileRequestDTO } from '../../types/api';

/**
 * Interim onboarding (until the Phase 1b taste steps): pick a display name and username, optionally
 * a photo, then PATCH /me → POST /me/onboarding/complete → tabs. ProfileGate sends every profile
 * without `onboardingCompletedAt` here.
 */
function SetUpProfile({ profile }: { profile: ProfileResponseDTO }) {
  const router = useRouter();
  const update = useUpdateMe();
  const complete = useCompleteOnboarding();
  const [displayName, setDisplayName] = useState(profile.displayName ?? '');
  const [username, setUsername] = useState(profile.username ?? '');

  const status = useUsernameCheck(username, profile.username);
  // A username is required: it must be the user's current one or confirmed available.
  const usernameReady = status === 'available' || status === 'unchanged' || status === 'error';
  const busy = update.isPending || complete.isPending;

  const fieldErrors = update.error instanceof ApiError ? update.error.fieldErrors : {};
  const saveError = update.isError && Object.keys(fieldErrors).length === 0 ? errorMessage(update.error) : null;
  const completeError = complete.isError ? errorMessage(complete.error) : null;

  async function next() {
    const body: UpdateProfileRequestDTO = {};
    const nextUsername = normalizeUsername(username);
    if (nextUsername !== profile.username) body.username = nextUsername;
    if (displayName.trim() !== (profile.displayName ?? '')) body.displayName = displayName.trim();
    complete.reset();
    try {
      if (Object.keys(body).length > 0) await update.mutateAsync(body);
      await complete.mutateAsync();
    } catch {
      return; // shown from the mutations' error state
    }
    router.replace('/');
  }

  return (
    <View className="w-full max-w-md gap-6 self-center pb-10">
      <View className="gap-2">
        <Heading size="lg">Set up your profile</Heading>
        <Body muted>Friends find you by your username. You can change any of this later.</Body>
      </View>
      <ProfilePhotoSection profile={profile} />
      <Card className="gap-4">
        <ProfileBasicsForm
          displayName={displayName}
          onDisplayNameChange={setDisplayName}
          username={username}
          onUsernameChange={setUsername}
          usernameStatus={status}
          fieldErrors={fieldErrors}
        />
        {saveError || completeError ? (
          <Body testID="onboarding-error" accessibilityLiveRegion="polite" className="text-velvet">
            {saveError ?? completeError}
          </Body>
        ) : null}
        <Button title="Continue" loading={busy} disabled={!usernameReady} onPress={() => void next()} />
      </Card>
      <Body muted className="text-center text-sm">
        You&apos;ll tell us about your taste in a later step.
      </Body>
    </View>
  );
}

export default function OnboardingScreen() {
  const me = useMe();
  const { signOut } = useAuth();

  let content;
  if (me.isPending) {
    content = <FullScreenLoader label="Loading your profile" />;
  } else if (me.isError) {
    content = (
      <ErrorState
        title="Couldn't load your profile"
        error={me.error}
        onRetry={() => void me.refetch()}
        retrying={me.isFetching}
      />
    );
  } else {
    content = <SetUpProfile profile={me.data} />;
  }

  return (
    <KeyboardAvoidingView className="flex-1" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll>
        {content}
        <View className="items-center gap-1 pb-4">
          <Body muted className="text-center text-sm">
            Not you?
          </Body>
          <Button variant="ghost" title="Sign out" onPress={() => void signOut()} />
        </View>
      </Screen>
    </KeyboardAvoidingView>
  );
}

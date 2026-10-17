import { useState } from 'react';
import { useUpdateMe } from '../../api/me';
import { normalizeUsername } from '../../api/usernames';
import { useAuth } from '../../auth/useAuth';
import { ApiError } from '../../lib/apiClient';
import type { ProfileResponseDTO, UpdateProfileRequestDTO } from '../../types/api';
import { ProfileBasicsForm, useUsernameCheck } from '../profile/ProfileBasicsForm';
import { ProfilePhotoSection } from '../profile/ProfilePhotoSection';
import { Button, Card, errorMessage } from '../ui';
import { OnboardingFrame } from './OnboardingFrame';
import { describeError } from './steps';
import { useAdvance } from './useAdvance';

/** Step 1: display name, username (live availability) and an optional photo. */
export function YouStep({ profile }: { profile: ProfileResponseDTO }) {
  const { signOut } = useAuth();
  const update = useUpdateMe();
  const nav = useAdvance(1);
  const [displayName, setDisplayName] = useState(profile.displayName ?? '');
  const [username, setUsername] = useState(profile.username ?? '');
  const status = useUsernameCheck(username, profile.username);
  // A username is required: the current one, or one confirmed available (a failed check lets the server decide).
  const usernameReady = status === 'available' || status === 'unchanged' || status === 'error';

  const fieldErrors = update.error instanceof ApiError ? update.error.fieldErrors : {};
  const saveError = update.isError && Object.keys(fieldErrors).length === 0 ? errorMessage(update.error) : null;

  async function next() {
    const body: UpdateProfileRequestDTO = {};
    const nextUsername = normalizeUsername(username);
    if (nextUsername !== profile.username) body.username = nextUsername;
    if (displayName.trim() !== (profile.displayName ?? '')) body.displayName = displayName.trim();
    nav.reset();
    try {
      if (Object.keys(body).length > 0) await update.mutateAsync(body);
    } catch {
      return; // shown from the mutation's error state
    }
    await nav.advance();
  }

  return (
    <OnboardingFrame
      step={1}
      title="Set up your profile"
      subtitle="Friends find you by your username. You can change any of this later."
      onContinue={() => void next()}
      continueDisabled={!usernameReady}
      loading={update.isPending || nav.isPending}
      error={saveError ?? describeError(nav.error)}
      footer={<Button variant="ghost" title="Sign out" onPress={() => void signOut()} />}
    >
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
      </Card>
    </OnboardingFrame>
  );
}

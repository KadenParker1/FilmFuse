import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useMe, useUpdateMe } from '../../api/me';
import { normalizeUsername } from '../../api/usernames';
import { ProfileBasicsForm, useUsernameCheck } from '../../components/profile/ProfileBasicsForm';
import { ProfilePhotoSection } from '../../components/profile/ProfilePhotoSection';
import { FullScreenLoader } from '../../components/FullScreenLoader';
import { Body, Button, Card, ErrorState, Heading, Screen, TextField, errorMessage } from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import { useTheme } from '../../theme/useTheme';
import type { ProfileResponseDTO, UpdateProfileRequestDTO } from '../../types/api';

const BIO_MAX = 300;

function EditForm({ profile }: { profile: ProfileResponseDTO }) {
  const router = useRouter();
  const update = useUpdateMe();
  const [displayName, setDisplayName] = useState(profile.displayName ?? '');
  const [username, setUsername] = useState(profile.username ?? '');
  const [bio, setBio] = useState(profile.bio ?? '');
  const [saved, setSaved] = useState(false);

  const status = useUsernameCheck(username, profile.username);

  useEffect(() => {
    if (!saved) return;
    const id = setTimeout(() => setSaved(false), 2500);
    return () => clearTimeout(id);
  }, [saved]);

  const serverErrors = update.error instanceof ApiError ? update.error.fieldErrors : {};
  const generalError =
    update.isError && Object.keys(serverErrors).length === 0 ? errorMessage(update.error) : null;
  const blockSave = status === 'taken' || status === 'invalid' || bio.length > BIO_MAX;

  function save() {
    const body: UpdateProfileRequestDTO = {};
    const nextUsername = normalizeUsername(username);
    if (nextUsername && nextUsername !== profile.username) body.username = nextUsername;
    if (displayName.trim() !== (profile.displayName ?? '')) body.displayName = displayName.trim();
    if (bio.trim() !== (profile.bio ?? '')) body.bio = bio.trim();
    if (Object.keys(body).length === 0) {
      router.back();
      return;
    }
    update.mutate(body, {
      onSuccess: () => {
        setSaved(true);
        if (router.canGoBack()) router.back();
      },
    });
  }

  return (
    <View className="gap-6">
    {/* The avatar's initials follow the name as it's typed. */}
    <ProfilePhotoSection profile={profile} displayName={displayName} username={username} />
    <Card className="gap-4">
      <ProfileBasicsForm
        displayName={displayName}
        onDisplayNameChange={setDisplayName}
        username={username}
        onUsernameChange={setUsername}
        usernameStatus={status}
        fieldErrors={serverErrors}
      />
      <View className="gap-1.5">
        <TextField
          label="Bio"
          value={bio}
          onChangeText={setBio}
          multiline
          maxLength={BIO_MAX}
          placeholder="A line or two about your taste"
          style={{ minHeight: 96, textAlignVertical: 'top' }}
          error={serverErrors.bio}
        />
        <Text
          testID="bio-counter"
          className={`self-end font-body text-xs ${bio.length >= BIO_MAX ? 'text-danger' : 'text-muted'}`}
        >
          {bio.length}/{BIO_MAX}
        </Text>
      </View>
      {generalError ? (
        <Body testID="save-error" accessibilityLiveRegion="polite" className="text-danger">
          {generalError}
        </Body>
      ) : null}
      {saved ? (
        <Body accessibilityLiveRegion="polite" className="text-success">
          Saved
        </Body>
      ) : null}
      <Button title="Save" loading={update.isPending} disabled={blockSave} onPress={save} />
    </Card>
    </View>
  );
}

export default function EditProfileScreen() {
  const router = useRouter();
  const { colors } = useTheme();
  const me = useMe();

  return (
    <KeyboardAvoidingView className="flex-1" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll>
        <View className="mb-6 flex-row items-center gap-3">
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Back"
            hitSlop={8}
            onPress={() => (router.canGoBack() ? router.back() : router.replace('/profile'))}
            className="h-10 w-10 items-center justify-center rounded-xl bg-raised active:opacity-70"
          >
            <Ionicons name="chevron-back" size={22} color={colors.text} />
          </Pressable>
          <Heading size="md">Edit profile</Heading>
        </View>
        {me.isPending ? (
          <FullScreenLoader label="Loading your profile" />
        ) : me.isError ? (
          <ErrorState
            title="Couldn't load your profile"
            error={me.error}
            onRetry={() => void me.refetch()}
            retrying={me.isFetching}
          />
        ) : (
          <View className="gap-6 pb-10">
            <EditForm profile={me.data} />
          </View>
        )}
      </Screen>
    </KeyboardAvoidingView>
  );
}

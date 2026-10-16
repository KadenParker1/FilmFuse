import { useEffect, useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useMe, useUpdateMe } from '../../api/me';
import { useRemoveProfilePhoto, useUploadProfilePhoto } from '../../api/profilePhoto';
import { normalizeUsername, usernameStatus, useUsernameAvailability } from '../../api/usernames';
import { canUseCamera, pickSquarePhoto, prepareProfilePhoto, type PhotoSource } from '../../components/profile/profilePhoto';
import { usernameStatusText } from '../../components/profile/usernameStatusText';
import { FullScreenLoader } from '../../components/FullScreenLoader';
import {
  Avatar,
  Body,
  Button,
  Card,
  ErrorState,
  Heading,
  Screen,
  Sheet,
  TextField,
  errorMessage,
} from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { useTheme } from '../../theme/useTheme';
import type { ProfileResponseDTO, UpdateProfileRequestDTO } from '../../types/api';

const BIO_MAX = 300;
const DISPLAY_NAME_MAX = 77;

function PhotoSection({ profile }: { profile: ProfileResponseDTO }) {
  const { colors } = useTheme();
  const upload = useUploadProfilePhoto();
  const remove = useRemoveProfilePhoto();
  const [menuOpen, setMenuOpen] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const busy = preparing || upload.isPending || remove.isPending;

  async function choose(source: PhotoSource) {
    setMenuOpen(false);
    setError(null);
    try {
      const picked = await pickSquarePhoto(source);
      if ('cancelled' in picked) return;
      if ('error' in picked) {
        setError(picked.error);
        return;
      }
      setPreparing(true);
      const jpeg = await prepareProfilePhoto(picked.uri);
      setPreparing(false);
      await upload.mutateAsync(jpeg);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setPreparing(false);
    }
  }

  async function removePhoto() {
    setMenuOpen(false);
    setError(null);
    try {
      await remove.mutateAsync();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  return (
    <View className="items-center gap-3">
      <Avatar uri={profile.profileImageUrl} displayName={profile.displayName} username={profile.username} size={112} />
      <View className="flex-row gap-2">
        <Button
          title={busy ? 'Uploading…' : 'Change photo'}
          variant="secondary"
          loading={busy}
          icon={<Ionicons name="camera-outline" size={18} color={colors.text} />}
          onPress={() => (canUseCamera ? setMenuOpen(true) : void choose('library'))}
        />
        {profile.profileImageUrl && !canUseCamera ? (
          <Button title="Remove photo" variant="ghost" disabled={busy} onPress={() => void removePhoto()} />
        ) : null}
      </View>
      {error ? (
        <Body testID="photo-error" accessibilityLiveRegion="polite" className="text-center text-velvet">
          {error}
        </Body>
      ) : null}
      <Sheet visible={menuOpen} onClose={() => setMenuOpen(false)} title="Profile photo">
        <View className="gap-3">
          <Button title="Choose from library" variant="secondary" onPress={() => void choose('library')} />
          <Button title="Take photo" variant="secondary" onPress={() => void choose('camera')} />
          {profile.profileImageUrl ? (
            <Button title="Remove photo" variant="danger" onPress={() => void removePhoto()} />
          ) : null}
          <Button title="Cancel" variant="ghost" onPress={() => setMenuOpen(false)} />
        </View>
      </Sheet>
    </View>
  );
}

function EditForm({ profile }: { profile: ProfileResponseDTO }) {
  const router = useRouter();
  const update = useUpdateMe();
  const [displayName, setDisplayName] = useState(profile.displayName ?? '');
  const [username, setUsername] = useState(profile.username ?? '');
  const [bio, setBio] = useState(profile.bio ?? '');
  const [saved, setSaved] = useState(false);

  const debouncedUsername = useDebouncedValue(username, 300);
  const availability = useUsernameAvailability(debouncedUsername, profile.username);
  const status = usernameStatus({
    username,
    current: profile.username,
    // Typing ahead of the debounce counts as still checking.
    isFetching: availability.isFetching || normalizeUsername(username) !== normalizeUsername(debouncedUsername),
    isError: availability.isError,
    data: availability.data,
  });
  const statusText = usernameStatusText(status);

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

  const toneClass = { muted: 'text-text-muted', good: 'text-success', bad: 'text-velvet' } as const;

  return (
    <Card className="gap-4">
      <TextField
        label="Display name"
        value={displayName}
        onChangeText={setDisplayName}
        maxLength={DISPLAY_NAME_MAX}
        autoComplete="name"
        placeholder="How friends see you"
        error={serverErrors.displayName}
      />
      <View className="gap-1.5">
        <TextField
          label="Username"
          value={username}
          onChangeText={setUsername}
          autoCapitalize="none"
          autoCorrect={false}
          autoComplete="username"
          maxLength={30}
          placeholder="e.g. popcorn_paula"
          error={serverErrors.username}
        />
        {!serverErrors.username && statusText ? (
          <Text
            testID="username-status"
            accessibilityLiveRegion="polite"
            className={`font-body text-sm ${toneClass[statusText.tone]}`}
          >
            {statusText.text}
          </Text>
        ) : null}
      </View>
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
          className={`self-end font-body text-xs ${bio.length >= BIO_MAX ? 'text-velvet' : 'text-text-muted'}`}
        >
          {bio.length}/{BIO_MAX}
        </Text>
      </View>
      {generalError ? (
        <Body testID="save-error" accessibilityLiveRegion="polite" className="text-velvet">
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
            className="h-10 w-10 items-center justify-center rounded-full border border-border bg-surface active:opacity-70"
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
            <PhotoSection profile={me.data} />
            <EditForm profile={me.data} />
          </View>
        )}
      </Screen>
    </KeyboardAvoidingView>
  );
}

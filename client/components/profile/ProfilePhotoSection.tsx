import { useState } from 'react';
import { View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useRemoveProfilePhoto, useUploadProfilePhoto } from '../../api/profilePhoto';
import { useTheme } from '../../theme/useTheme';
import type { ProfileResponseDTO } from '../../types/api';
import { Avatar, Body, Button, Sheet, errorMessage } from '../ui';
import { canUseCamera, pickSquarePhoto, prepareProfilePhoto, type PhotoSource } from './profilePhoto';

/**
 * The avatar with Change / Remove photo: pick (library, or camera on native) → square 512px JPEG →
 * upload. Updates the `me` cache, so the avatar refreshes everywhere. Used by edit profile and onboarding.
 */
export function ProfilePhotoSection({ profile }: { profile: ProfileResponseDTO }) {
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

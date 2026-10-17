import { useState } from 'react';
import { View } from 'react-native';
import { useDeleteAccount } from '../../api/me';
import { Body, Button, Sheet, TextField, errorMessage } from '../ui';

export const DELETE_CONFIRMATION = 'DELETE';

type Props = { visible: boolean; onClose: () => void };

/**
 * In-app confirmation for permanent account deletion: the user must type DELETE. On success the
 * hook signs out and clears the cache, and the root guard routes back to sign-in.
 */
export function DeleteAccountSheet({ visible, onClose }: Props) {
  const [typed, setTyped] = useState('');
  const del = useDeleteAccount();
  const confirmed = typed.trim() === DELETE_CONFIRMATION;

  const close = () => {
    if (del.isPending) return;
    setTyped('');
    del.reset();
    onClose();
  };

  return (
    <Sheet visible={visible} onClose={close} title="Delete your account?">
      <View className="gap-4">
        <Body muted>
          This permanently deletes your FilmFuse account, profile and photo. It can&apos;t be undone.
        </Body>
        <TextField
          label={`Type ${DELETE_CONFIRMATION} to confirm`}
          value={typed}
          onChangeText={setTyped}
          autoCapitalize="characters"
          autoCorrect={false}
          placeholder={DELETE_CONFIRMATION}
        />
        {del.isError ? (
          <Body testID="delete-error" accessibilityLiveRegion="polite" className="text-danger">
            {errorMessage(del.error)}
          </Body>
        ) : null}
        <Button
          title="Delete account forever"
          variant="danger"
          disabled={!confirmed}
          loading={del.isPending}
          onPress={() => del.mutate()}
        />
        <Button title="Cancel" variant="ghost" disabled={del.isPending} onPress={close} />
      </View>
    </Sheet>
  );
}

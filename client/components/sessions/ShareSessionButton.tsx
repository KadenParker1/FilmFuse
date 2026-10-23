import { useState } from 'react';
import { View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../../theme/useTheme';
import { Body, Button, errorMessage } from '../ui';
import { shareSession } from './shareSession';

/**
 * Shares the session's web link: the share sheet on native, the Web Share API or a copied link on
 * web. Only members (or group members, who may join) can open it.
 */
export function ShareSessionButton({ url }: { url: string }) {
  const { colors } = useTheme();
  const [sharing, setSharing] = useState(false);
  const [note, setNote] = useState<{ text: string; error: boolean } | null>(null);

  async function share() {
    setNote(null);
    setSharing(true);
    try {
      const outcome = await shareSession(url);
      if (outcome === 'copied') setNote({ text: 'Link copied.', error: false });
    } catch (e) {
      setNote({ text: errorMessage(e), error: true });
    } finally {
      setSharing(false);
    }
  }

  return (
    <View className="gap-1">
      <Button
        size="sm"
        variant="secondary"
        title="Share"
        accessibilityLabel="Share this session"
        loading={sharing}
        icon={<Ionicons name="share-outline" size={16} color={colors.text} />}
        onPress={() => void share()}
      />
      {note ? (
        <Body
          testID="share-note"
          accessibilityLiveRegion="polite"
          className={`text-xs ${note.error ? 'text-danger' : 'text-success'}`}
        >
          {note.text}
        </Body>
      ) : null}
    </View>
  );
}

import { useState } from 'react';
import { View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useCreateInvite } from '../../api/invites';
import { useTheme } from '../../theme/useTheme';
import { Body, Button, Card, Heading, errorMessage } from '../ui';
import { shareInvite } from './shareInvite';

/** "Invite friends": creates a fresh link and hands it to the share sheet (or the clipboard on web). */
export function InviteCard() {
  const { colors } = useTheme();
  const create = useCreateInvite();
  const [sharing, setSharing] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function invite() {
    setError(null);
    setCopied(false);
    setSharing(true);
    try {
      const { url } = await create.mutateAsync();
      const outcome = await shareInvite(url);
      if (outcome === 'copied') setCopied(true);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSharing(false);
    }
  }

  return (
    <Card raised className="gap-3">
      <View className="flex-row items-center gap-3">
        <View className="h-10 w-10 items-center justify-center rounded-full bg-gold">
          <Ionicons name="ticket-outline" size={20} color={colors.onGold} />
        </View>
        <Heading size="sm" className="flex-1">
          Invite friends
        </Heading>
      </View>
      <Body muted>Share a link. Anyone who opens it and signs in becomes your friend right away.</Body>
      <Button
        title="Share invite link"
        loading={sharing}
        icon={<Ionicons name="share-outline" size={18} color={colors.onGold} />}
        onPress={() => void invite()}
      />
      {copied ? (
        <Body testID="invite-copied" accessibilityLiveRegion="polite" className="text-center text-success">
          Link copied. Paste it anywhere to invite a friend.
        </Body>
      ) : null}
      {error ? (
        <Body testID="invite-error" accessibilityLiveRegion="polite" className="text-center text-velvet">
          {error}
        </Body>
      ) : null}
    </Card>
  );
}

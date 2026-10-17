import { useEffect, useRef } from 'react';
import { View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useRedeemInvite } from '../../api/invites';
import { useAuth } from '../../auth/useAuth';
import { FullScreenLoader } from '../../components/FullScreenLoader';
import { SaveLinkAndSignIn } from '../../components/SaveLinkAndSignIn';
import { inviteFailureCopy } from '../../components/social/inviteFailureCopy';
import { Avatar, Body, Button, EmptyState, ErrorState, Heading, Screen } from '../../components/ui';
import { personName } from '../../lib/format';
import { invitePath } from '../../lib/pendingLinkPath';
import { useTheme } from '../../theme/useTheme';

const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

/** Signed in: redeems the token once and shows who you're now friends with. */
function RedeemInvite({ token }: { token: string }) {
  const router = useRouter();
  const { colors } = useTheme();
  const redeem = useRedeemInvite();
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    redeem.mutate(token);
  }, [redeem, token]);

  const goHome = <Button title="Go to Social" variant="ghost" onPress={() => router.replace('/')} />;

  if (redeem.isError) {
    const copy = inviteFailureCopy(redeem.error);
    return (
      <Screen>
        {copy ? (
          <EmptyState icon="ticket-outline" title={copy.title} message={copy.message} />
        ) : (
          <ErrorState
            title="Couldn't accept this invite"
            error={redeem.error}
            onRetry={() => redeem.mutate(token)}
            retrying={redeem.isPending}
          />
        )}
        <View className="w-full max-w-sm self-center pb-6">{goHome}</View>
      </Screen>
    );
  }

  if (!redeem.isSuccess) return <FullScreenLoader label="Accepting invite" />;

  const { inviter } = redeem.data;
  const name = personName(inviter);
  return (
    <Screen>
      <View className="flex-1 items-center justify-center gap-4">
        <View className="relative">
          <Avatar
            uri={inviter.profileImageUrl}
            displayName={inviter.displayName}
            username={inviter.username}
            size={112}
          />
          <View className="absolute -bottom-1 -right-1 h-9 w-9 items-center justify-center rounded-full bg-accent">
            <Ionicons name="checkmark" size={20} color={colors.onAccent} />
          </View>
        </View>
        <Heading size="md" className="text-center">
          You and {name} are now friends
        </Heading>
        <Body muted className="max-w-sm text-center">
          Their ratings will show up in your feed, and you can add each other to groups.
        </Body>
        <View className="w-full max-w-sm gap-3 pt-2">
          <Button title={`View ${name}'s profile`} onPress={() => router.replace(`/user/${inviter.id}`)} />
          {goHome}
        </View>
      </View>
    </Screen>
  );
}

/**
 * `/invite/<token>` — reachable signed in or out (see the root layout). A token opened while
 * signed out is redeemed by the tabs layout after sign-in.
 */
export default function InviteScreen() {
  const { session } = useAuth();
  const router = useRouter();
  const token = first(useLocalSearchParams<{ token: string }>().token)?.trim();

  if (!token) {
    return (
      <Screen>
        <EmptyState icon="ticket-outline" title="This invite isn't valid" message="The link is missing its code." />
        <View className="w-full max-w-sm self-center pb-6">
          <Button title="Go to FilmFuse" onPress={() => router.replace('/')} />
        </View>
      </Screen>
    );
  }
  return session ? (
    <RedeemInvite token={token} />
  ) : (
    <SaveLinkAndSignIn path={invitePath(token)} label="Saving your invite" />
  );
}

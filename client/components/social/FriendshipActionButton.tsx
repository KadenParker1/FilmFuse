import type { ComponentProps } from 'react';
import { Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useAcceptRequest, useSendFriendRequest } from '../../api/social';
import { personName } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { FriendshipState } from '../../types/api';
import { Button, errorMessage } from '../ui';
import type { RowUser } from './UserRow';

type IconName = ComponentProps<typeof Ionicons>['name'];

/** A non-interactive state label ("Friends", "Requested", "Blocked"). */
export function StatusPill({
  label,
  icon,
  accessibilityLabel,
}: {
  label: string;
  icon: IconName;
  accessibilityLabel: string;
}) {
  const { colors } = useTheme();
  return (
    <View
      accessible
      accessibilityRole="text"
      accessibilityLabel={accessibilityLabel}
      className="min-h-9 flex-row items-center gap-1.5 rounded-full border border-border px-3 py-1.5"
    >
      <Ionicons name={icon} size={14} color={colors.muted} />
      <Text className="font-body-medium text-sm text-muted">{label}</Text>
    </View>
  );
}

type Props = { user: RowUser; state: FriendshipState };

/**
 * The compact, state-aware action in a person row (search results): Add / Accept, or a label for
 * states that need a deliberate choice on the profile (Requested, Friends, Blocked).
 */
export function FriendshipActionButton({ user, state }: Props) {
  const { colors } = useTheme();
  const send = useSendFriendRequest();
  const accept = useAcceptRequest();
  const name = personName(user);
  const failed = send.isError ? send.error : accept.isError ? accept.error : null;

  let control;
  switch (state) {
    case 'NONE':
      control = (
        <Button
          size="sm"
          title="Add"
          accessibilityLabel={`Add ${name} as a friend`}
          loading={send.isPending}
          icon={<Ionicons name="person-add-outline" size={14} color={colors.onAccent} />}
          onPress={() => send.mutate(user.id)}
        />
      );
      break;
    case 'REQUEST_RECEIVED':
      control = (
        <Button
          size="sm"
          title="Accept"
          accessibilityLabel={`Accept friend request from ${name}`}
          loading={accept.isPending}
          onPress={() => accept.mutate(user.id)}
        />
      );
      break;
    case 'REQUEST_SENT':
      control = (
        <StatusPill label="Requested" icon="time-outline" accessibilityLabel={`Friend request sent to ${name}`} />
      );
      break;
    case 'FRIENDS':
      control = <StatusPill label="Friends" icon="checkmark" accessibilityLabel={`You and ${name} are friends`} />;
      break;
    case 'BLOCKED':
      control = <StatusPill label="Blocked" icon="ban-outline" accessibilityLabel={`You blocked ${name}`} />;
      break;
  }

  return (
    <View className="items-end gap-1">
      {control}
      {failed ? (
        <Text accessibilityRole="alert" className="max-w-40 text-right font-body text-xs text-danger" numberOfLines={2}>
          {errorMessage(failed)}
        </Text>
      ) : null}
    </View>
  );
}

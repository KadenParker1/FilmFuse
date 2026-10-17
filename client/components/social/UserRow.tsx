import type { ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import { personName } from '../../lib/format';
import { Avatar, Skeleton } from '../ui';

export type RowUser = {
  id: string;
  username: string | null;
  displayName: string | null;
  profileImageUrl: string | null;
};

type Props = {
  user: RowUser;
  /** A muted second line under @username, e.g. "3 mutual friends". */
  subtitle?: string;
  /** Opens the person's profile. Without it the row isn't pressable. */
  onPress?: () => void;
  /** Right-hand actions (buttons, toggles). Kept outside the pressable area. */
  children?: ReactNode;
  testID?: string;
};

/** A person in a list: avatar, name, @username, optional subtitle and trailing actions. */
export function UserRow({ user, subtitle, onPress, children, testID }: Props) {
  const name = personName(user);
  const showHandle = !!user.displayName?.trim() && !!user.username;
  return (
    <View testID={testID ?? `user-row-${user.id}`} className="min-h-16 flex-row items-center gap-3 py-2">
      <Pressable
        accessibilityRole={onPress ? 'button' : undefined}
        accessibilityLabel={`${name}${showHandle ? `, @${user.username}` : ''}`}
        accessibilityHint={onPress ? 'Opens their profile' : undefined}
        disabled={!onPress}
        onPress={onPress}
        className="flex-1 flex-row items-center gap-3 active:opacity-70"
      >
        <Avatar uri={user.profileImageUrl} displayName={user.displayName} username={user.username} size={44} />
        <View className="flex-1">
          <Text className="font-body-semibold text-base text-text" numberOfLines={1}>
            {name}
          </Text>
          {showHandle ? (
            <Text className="font-body text-sm text-muted" numberOfLines={1}>
              @{user.username}
            </Text>
          ) : null}
          {subtitle ? (
            <Text className="font-body text-xs text-muted" numberOfLines={1}>
              {subtitle}
            </Text>
          ) : null}
        </View>
      </Pressable>
      {children ? <View className="flex-row items-center gap-2">{children}</View> : null}
    </View>
  );
}

/** Placeholder rows while a list loads. */
export function UserRowSkeletons({ count = 3, testID = 'user-row-skeleton' }: { count?: number; testID?: string }) {
  return (
    <View testID={testID} accessibilityLabel="Loading" className="gap-1">
      {Array.from({ length: count }, (_, i) => (
        <View key={i} className="min-h-16 flex-row items-center gap-3 py-2">
          <Skeleton className="h-11 w-11 rounded-full" />
          <View className="flex-1 gap-2">
            <Skeleton className="h-4 w-1/2" />
            <Skeleton className="h-3 w-1/3" />
          </View>
        </View>
      ))}
    </View>
  );
}

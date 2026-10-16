import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { flattenPages } from '../../api/movies';
import { useFriends } from '../../api/social';
import { personName } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { FriendDTO } from '../../types/api';
import { Avatar, Body, Button, ErrorState, Label, SearchBar } from '../ui';
import { UserRowSkeletons } from '../social/UserRow';

/** Case-insensitive match on display name or username. */
export function matchesFriend(friend: Pick<FriendDTO, 'displayName' | 'username'>, query: string): boolean {
  const q = query.trim().toLowerCase().replace(/^@/, '');
  if (!q) return true;
  return [friend.displayName, friend.username].some((s) => !!s && s.toLowerCase().includes(q));
}

type Props = {
  selected: string[];
  onChange: (ids: string[]) => void;
  /** Most people that may be picked. */
  max: number;
  /** Friends already in the group, hidden from the list. */
  excludeIds?: string[];
  title?: string;
};

/** Multi-select over the caller's friends with an in-list search and a selection cap. */
export function FriendPicker({ selected, onChange, max, excludeIds = [], title = 'Friends' }: Props) {
  const { colors } = useTheme();
  const friends = useFriends();
  const [query, setQuery] = useState('');
  const all = flattenPages(friends.data);
  const visible = all.filter((f) => !excludeIds.includes(f.id) && matchesFriend(f, query));
  const full = selected.length >= max;

  const toggle = (id: string) => {
    if (selected.includes(id)) onChange(selected.filter((s) => s !== id));
    else if (!full) onChange([...selected, id]);
  };

  let list;
  if (friends.isPending) {
    list = <UserRowSkeletons count={3} testID="friend-picker-loading" />;
  } else if (friends.isError && all.length === 0) {
    list = (
      <ErrorState
        compact
        title="Couldn't load your friends"
        error={friends.error}
        onRetry={() => void friends.refetch()}
        retrying={friends.isFetching}
      />
    );
  } else if (all.length === 0) {
    list = (
      <Body muted testID="friend-picker-empty" className="py-4 text-center">
        Add friends on the Social tab to invite them here.
      </Body>
    );
  } else if (visible.length === 0) {
    list = (
      <Body muted className="py-4 text-center">
        No friends match “{query.trim()}”.
      </Body>
    );
  } else {
    list = visible.map((f) => {
      const checked = selected.includes(f.id);
      const disabled = !checked && full;
      return (
        <Pressable
          key={f.id}
          accessibilityRole="checkbox"
          accessibilityLabel={personName(f)}
          accessibilityState={{ checked, disabled }}
          disabled={disabled}
          onPress={() => toggle(f.id)}
          className={`min-h-14 flex-row items-center gap-3 py-2 active:opacity-70 ${disabled ? 'opacity-50' : ''}`}
        >
          <Avatar uri={f.profileImageUrl} displayName={f.displayName} username={f.username} size={40} />
          <View className="flex-1">
            <Text className="font-body-semibold text-base text-text" numberOfLines={1}>
              {personName(f)}
            </Text>
            {f.displayName && f.username ? (
              <Text className="font-body text-sm text-text-muted">@{f.username}</Text>
            ) : null}
          </View>
          <Ionicons
            name={checked ? 'checkmark-circle' : 'ellipse-outline'}
            size={24}
            color={checked ? colors.gold : colors.textMuted}
          />
        </Pressable>
      );
    });
  }

  return (
    <View className="gap-2">
      <View className="flex-row items-center justify-between">
        <Label accessibilityRole="header">{title}</Label>
        <Text testID="friend-picker-count" className="font-body text-sm text-text-muted">
          {selected.length} of {max} selected
        </Text>
      </View>
      {full ? (
        <Body accessibilityLiveRegion="polite" className="text-sm text-velvet">
          That’s the most you can add.
        </Body>
      ) : null}
      <SearchBar value={query} onChangeText={setQuery} label="Search your friends" />
      <View>{list}</View>
      {friends.hasNextPage ? (
        <Button
          size="sm"
          variant="ghost"
          title="Load more friends"
          loading={friends.isFetchingNextPage}
          onPress={() => void friends.fetchNextPage()}
        />
      ) : null}
    </View>
  );
}

import { useState } from 'react';
import { View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useFeed } from '../../api/feed';
import { useTheme } from '../../theme/useTheme';
import type { FeedScope } from '../../types/api';
import { useQuickRate } from '../rating/QuickRateProvider';
import { Button, ChoiceGroup, EmptyState, type Choice } from '../ui';
import { FeedList } from './FeedList';

export const SCOPE_CHOICES: Choice<FeedScope>[] = [
  { value: 'FRIENDS', label: 'Friends' },
  { value: 'EVERYONE', label: 'Everyone' },
];

function FriendsEmpty({ onFindFriends }: { onFindFriends: () => void }) {
  const { colors } = useTheme();
  const { openRatePicker } = useQuickRate();
  return (
    <View testID="feed-empty-friends" className="flex-1">
      <EmptyState
        icon="chatbubbles-outline"
        title="Rate a movie or add friends to fill your feed"
        message="Your ratings and your friends' ratings show up here, newest first."
      />
      <View className="w-full max-w-sm gap-3 self-center">
        <Button
          title="Rate a movie"
          icon={<Ionicons name="star-outline" size={18} color={colors.onGold} />}
          onPress={openRatePicker}
        />
        <Button title="Find friends" variant="secondary" onPress={onFindFriends} />
      </View>
    </View>
  );
}

/** The Social tab's Feed segment: Friends / Everyone, newest ratings first. */
export function FeedPanel({ onFindFriends }: { onFindFriends: () => void }) {
  const [scope, setScope] = useState<FeedScope>('FRIENDS');
  const feed = useFeed(scope);

  return (
    <View testID="feed-panel" className="flex-1 gap-3">
      <ChoiceGroup label="Feed audience" choices={SCOPE_CHOICES} value={scope} onChange={setScope} layout="fill" />
      <FeedList
        key={scope}
        testID={`feed-list-${scope.toLowerCase()}`}
        query={feed}
        errorTitle="Couldn't load your feed"
        empty={
          scope === 'FRIENDS' ? (
            <FriendsEmpty onFindFriends={onFindFriends} />
          ) : (
            <View testID="feed-empty-everyone" className="flex-1">
              <EmptyState
                icon="globe-outline"
                title="No public ratings yet"
                message="Ratings people share publicly will show up here."
              />
            </View>
          )
        }
      />
    </View>
  );
}

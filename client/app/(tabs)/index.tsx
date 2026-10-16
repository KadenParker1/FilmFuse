import { useState } from 'react';
import { ScrollView, View } from 'react-native';
import { useFriendRequests } from '../../api/social';
import { USER_SEARCH_MAX_LENGTH } from '../../api/users';
import { FriendsPanel } from '../../components/social/FriendsPanel';
import { UserSearchResults } from '../../components/social/UserSearchResults';
import { Button, EmptyState, Heading, Screen, SearchBar, SegmentedControl } from '../../components/ui';
import { useDebouncedValue } from '../../lib/useDebouncedValue';

const SEARCH_DEBOUNCE_MS = 300;

type SocialSegment = 'feed' | 'friends';

function FeedPlaceholder({ onFindFriends }: { onFindFriends: () => void }) {
  return (
    <View testID="feed-empty" className="flex-1 pb-28">
      <EmptyState
        icon="chatbubbles-outline"
        title="Your friends' ratings will appear here"
        message="When your friends rate and review movies, their takes show up in this feed. Reviews arrive in Phase 1b."
      />
      <View className="w-full max-w-sm self-center">
        <Button title="Find friends" variant="secondary" onPress={onFindFriends} />
      </View>
    </View>
  );
}

export default function SocialScreen() {
  const [input, setInput] = useState('');
  const [segment, setSegment] = useState<SocialSegment>('feed');
  const query = useDebouncedValue(input.trim(), SEARCH_DEBOUNCE_MS);
  const searching = input.trim().length > 0;
  const incoming = useFriendRequests('INCOMING');
  const incomingCount = incoming.data?.length ?? 0;

  return (
    <Screen edges={['top']} className="gap-4 pb-0">
      <Heading>Social</Heading>
      <SearchBar
        label="Search people"
        placeholder="Search by name or @username"
        value={input}
        onChangeText={setInput}
        maxLength={USER_SEARCH_MAX_LENGTH}
      />
      {searching ? (
        <View className="flex-1">
          <UserSearchResults query={query} />
        </View>
      ) : (
        <>
          <SegmentedControl
            label="Social sections"
            value={segment}
            onChange={setSegment}
            segments={[
              { value: 'feed', label: 'Feed' },
              { value: 'friends', label: 'Friends', badge: incomingCount },
            ]}
          />
          {segment === 'feed' ? (
            <FeedPlaceholder onFindFriends={() => setSegment('friends')} />
          ) : (
            <ScrollView
              testID="friends-scroll"
              keyboardShouldPersistTaps="handled"
              showsVerticalScrollIndicator={false}
            >
              <FriendsPanel />
            </ScrollView>
          )}
        </>
      )}
    </Screen>
  );
}

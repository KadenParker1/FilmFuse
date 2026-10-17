import { useState } from 'react';
import { View } from 'react-native';
import { USER_SEARCH_MAX_LENGTH } from '../../api/users';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { InviteCard } from '../social/InviteCard';
import { UserSearchResults } from '../social/UserSearchResults';
import { SearchBar } from '../ui';
import { OnboardingFrame } from './OnboardingFrame';
import { describeError } from './steps';
import { useAdvance } from './useAdvance';

const SEARCH_DEBOUNCE_MS = 300;

/** Step 7: find friends by username or share an invite link. Optional. */
export function FriendsStep() {
  const nav = useAdvance(7);
  const [input, setInput] = useState('');
  const query = useDebouncedValue(input.trim(), SEARCH_DEBOUNCE_MS);
  const searching = input.trim().length > 0;

  return (
    <OnboardingFrame
      step={7}
      title="Find your friends"
      subtitle="FilmFuse is best with friends: you’ll get group picks for movie night."
      scroll={false}
      onBack={nav.back}
      onContinue={() => void nav.advance()}
      loading={nav.isPending}
      error={describeError(nav.error)}
    >
      <SearchBar
        label="Search people"
        placeholder="Search by name or @username"
        value={input}
        onChangeText={setInput}
        maxLength={USER_SEARCH_MAX_LENGTH}
      />
      <View className="flex-1">{searching ? <UserSearchResults query={query} /> : <InviteCard />}</View>
    </OnboardingFrame>
  );
}

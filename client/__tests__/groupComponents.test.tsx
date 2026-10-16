jest.mock('../api/social', () => ({ useFriends: jest.fn() }));
jest.mock('../api/groups', () => ({ ...jest.requireActual('../api/groups'), useUpdateGroup: jest.fn() }));

import { useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { useUpdateGroup } from '../api/groups';
import { useFriends } from '../api/social';
import { AvatarStack } from '../components/groups/AvatarStack';
import { FriendPicker, matchesFriend } from '../components/groups/FriendPicker';
import { GroupCard } from '../components/groups/GroupCard';
import { GroupSettingsSheet } from '../components/groups/GroupSettingsSheet';
import { ColorSwatches, EmojiPicker } from '../components/groups/GroupStylePickers';
import {
  DEFAULT_GROUP_COLOR,
  DEFAULT_GROUP_EMOJI,
  GROUP_COLORS,
  GROUP_EMOJIS,
  groupColor,
  groupEmoji,
} from '../components/groups/groupStyle';
import { validateGroupForm } from '../components/groups/validateGroup';
import { Button } from '../components/ui';
import { ApiError, NetworkError } from '../lib/apiClient';
import { timeAgo } from '../lib/format';
import { makeFriend, makeGroup, makeGroupSummary, makeUser } from '../test-utils/fixtures';
import { infiniteResult, mutationResult } from '../test-utils/queryResults';

const mockFriends = useFriends as jest.Mock;
const mockUpdate = useUpdateGroup as jest.Mock;
let update: ReturnType<typeof mutationResult>;

const friends = [
  makeFriend({ id: 'f1', displayName: 'Grace Hopper', username: 'grace' }),
  makeFriend({ id: 'f2', displayName: 'Alan Turing', username: 'alan' }),
  makeFriend({ id: 'f3', displayName: null, username: 'joan' }),
];

beforeEach(() => {
  mockFriends.mockReturnValue(infiniteResult({ items: friends }));
  update = mutationResult();
  mockUpdate.mockImplementation(() => update);
});

describe('groupStyle', () => {
  it('offers 24 emojis and a palette of valid hex colours', () => {
    expect(GROUP_EMOJIS).toHaveLength(24);
    expect(new Set(GROUP_EMOJIS).size).toBe(24);
    for (const c of GROUP_COLORS) expect(c.hex).toMatch(/^#[0-9A-F]{6}$/);
  });

  it('falls back for unset or malformed values', () => {
    expect(groupColor(null)).toBe(DEFAULT_GROUP_COLOR);
    expect(groupColor('red')).toBe(DEFAULT_GROUP_COLOR);
    expect(groupColor('#123abc')).toBe('#123abc');
    expect(groupEmoji(null)).toBe(DEFAULT_GROUP_EMOJI);
    expect(groupEmoji('  ')).toBe(DEFAULT_GROUP_EMOJI);
    expect(groupEmoji('🍿')).toBe('🍿');
  });
});

describe('validateGroupForm', () => {
  it('requires a name of at most 40 characters', () => {
    expect(validateGroupForm('', []).name).toMatch(/name/);
    expect(validateGroupForm('   ', []).name).toMatch(/name/);
    expect(validateGroupForm('x'.repeat(41), []).name).toMatch(/40/);
    expect(validateGroupForm(` ${'x'.repeat(40)} `, [])).toEqual({});
  });

  it('allows at most 19 other members', () => {
    const ids = (n: number) => Array.from({ length: n }, (_, i) => `u${i}`);
    expect(validateGroupForm('Club', ids(19))).toEqual({});
    expect(validateGroupForm('Club', ids(20)).members).toMatch(/19/);
  });
});

describe('timeAgo', () => {
  const now = new Date('2026-10-16T12:00:00Z');
  it.each([
    [null, null],
    ['2026-10-16T11:59:40Z', 'just now'],
    ['2026-10-16T11:55:00Z', '5 minutes ago'],
    ['2026-10-16T11:00:00Z', '1 hour ago'],
    ['2026-10-14T12:00:00Z', '2 days ago'],
  ])('%p → %p', (iso, text) => expect(timeAgo(iso, now)).toBe(text));

  it('shows a date past a week, and null for junk', () => {
    expect(timeAgo('2026-09-01T12:00:00Z', now)).toMatch(/2026/);
    expect(timeAgo('not a date', now)).toBeNull();
  });
});

describe('AvatarStack', () => {
  it('shows up to max avatars and a +N overflow, read as one label', async () => {
    const users = ['a', 'b', 'c'].map((id) => makeUser({ id, displayName: id.toUpperCase(), username: id }));
    await render(<AvatarStack users={users} total={7} max={2} />);
    expect(screen.getAllByTestId('avatar-initials')).toHaveLength(2);
    expect(screen.getByTestId('avatar-stack-extra')).toHaveTextContent('+5');
    expect(screen.getByLabelText('A, B and 5 more')).toBeOnTheScreen();
  });

  it('has no overflow when everyone fits', async () => {
    await render(<AvatarStack users={[makeUser()]} />);
    expect(screen.queryByTestId('avatar-stack-extra')).toBeNull();
    expect(screen.getByLabelText('Grace Hopper')).toBeOnTheScreen();
  });
});

describe('GroupCard', () => {
  it('shows emoji, name, member count and last session; opens on press', async () => {
    const onPress = jest.fn();
    await render(<GroupCard group={makeGroupSummary({ lastSessionAt: null })} onPress={onPress} />);
    expect(screen.getByText('🎬')).toBeOnTheScreen();
    expect(screen.getByText('Film club')).toBeOnTheScreen();
    expect(screen.getByText('3 members · No movie nights yet')).toBeOnTheScreen();
    expect(screen.getByTestId('group-card-g1').props.style).toMatchObject({ borderLeftColor: '#C7354A' });
    await fireEvent.press(screen.getByRole('button', { name: /^Film club/ }));
    expect(onPress).toHaveBeenCalled();
  });

  it('mentions the last movie night and renders actions for invites', async () => {
    await render(
      <GroupCard group={makeGroupSummary({ memberStatus: 'INVITED', emoji: null, color: null })}>
        <Button title="Accept" />
      </GroupCard>,
    );
    expect(screen.getByText('You’re invited')).toBeOnTheScreen();
    expect(screen.getByText(DEFAULT_GROUP_EMOJI)).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Accept' })).toBeOnTheScreen();
  });

  it('shows how long ago the last session was', async () => {
    await render(<GroupCard group={makeGroupSummary({ lastSessionAt: new Date(Date.now() - 2 * 3600_000).toISOString() })} />);
    expect(screen.getByText('3 members · Last movie night 2 hours ago')).toBeOnTheScreen();
  });
});

describe('EmojiPicker / ColorSwatches', () => {
  it('selects an emoji', async () => {
    const onChange = jest.fn();
    await render(<EmojiPicker value="🎬" onChange={onChange} />);
    expect(screen.getAllByRole('radio')).toHaveLength(24);
    expect(screen.getByRole('radio', { name: 'Emoji 🎬' })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'Emoji 🍿' }));
    expect(onChange).toHaveBeenCalledWith('🍿');
  });

  it('selects a colour (case-insensitively matched)', async () => {
    const onChange = jest.fn();
    await render(<ColorSwatches value="#c7354a" onChange={onChange} />);
    expect(screen.getByRole('radio', { name: 'Velvet red' })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'Neon blue' }));
    expect(onChange).toHaveBeenCalledWith('#3DA5D9');
  });
});

describe('FriendPicker', () => {
  function Harness({ max = 19, excludeIds }: { max?: number; excludeIds?: string[] }) {
    const [selected, setSelected] = useState<string[]>([]);
    return <FriendPicker selected={selected} onChange={setSelected} max={max} excludeIds={excludeIds} />;
  }

  it('matches on display name or username', () => {
    expect(matchesFriend({ displayName: 'Grace Hopper', username: 'grace' }, 'hop')).toBe(true);
    expect(matchesFriend({ displayName: null, username: 'joan' }, '@JO')).toBe(true);
    expect(matchesFriend({ displayName: 'Alan', username: 'alan' }, 'zz')).toBe(false);
    expect(matchesFriend({ displayName: 'Alan', username: 'alan' }, '  ')).toBe(true);
  });

  it('toggles friends and counts the selection', async () => {
    await render(<Harness />);
    expect(screen.getByTestId('friend-picker-count')).toHaveTextContent('0 of 19 selected');
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Grace Hopper' }));
    expect(screen.getByRole('checkbox', { name: 'Grace Hopper' })).toBeChecked();
    expect(screen.getByTestId('friend-picker-count')).toHaveTextContent('1 of 19 selected');
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Grace Hopper' }));
    expect(screen.getByRole('checkbox', { name: 'Grace Hopper' })).not.toBeChecked();
  });

  it('filters by search and says when nothing matches', async () => {
    await render(<Harness />);
    await fireEvent.changeText(screen.getByLabelText('Search your friends'), 'ala');
    expect(screen.getByRole('checkbox', { name: 'Alan Turing' })).toBeOnTheScreen();
    expect(screen.queryByRole('checkbox', { name: 'Grace Hopper' })).toBeNull();
    await fireEvent.changeText(screen.getByLabelText('Search your friends'), 'zzz');
    expect(screen.getByText('No friends match “zzz”.')).toBeOnTheScreen();
  });

  it('disables the rest once the cap is reached', async () => {
    await render(<Harness max={1} />);
    await fireEvent.press(screen.getByRole('checkbox', { name: 'Grace Hopper' }));
    expect(screen.getByRole('checkbox', { name: 'Alan Turing' })).toBeDisabled();
    expect(screen.getByText('That’s the most you can add.')).toBeOnTheScreen();
  });

  it('hides excluded people', async () => {
    await render(<Harness excludeIds={['f1']} />);
    expect(screen.queryByRole('checkbox', { name: 'Grace Hopper' })).toBeNull();
    expect(screen.getByRole('checkbox', { name: '@joan' })).toBeOnTheScreen();
  });

  it('loads more pages', async () => {
    const fetchNextPage = jest.fn();
    mockFriends.mockReturnValue(infiniteResult({ items: friends, hasNextPage: true, fetchNextPage }));
    await render(<Harness />);
    await fireEvent.press(screen.getByRole('button', { name: 'Load more friends' }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('loading, empty and error states', async () => {
    mockFriends.mockReturnValue(infiniteResult());
    const { rerender } = await render(<Harness />);
    expect(screen.getByTestId('friend-picker-loading')).toBeOnTheScreen();
    mockFriends.mockReturnValue(infiniteResult({ items: [] }));
    await rerender(<Harness />);
    expect(screen.getByTestId('friend-picker-empty')).toBeOnTheScreen();
    const refetch = jest.fn();
    mockFriends.mockReturnValue(
      infiniteResult({ isPending: false, isError: true, error: new NetworkError(), refetch }),
    );
    await rerender(<Harness />);
    expect(screen.getByText("Couldn't load your friends")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });
});

describe('GroupSettingsSheet', () => {
  it('saves name, emoji, colour and the comfort cap', async () => {
    const onClose = jest.fn();
    await render(<GroupSettingsSheet group={makeGroup()} visible onClose={onClose} onDelete={jest.fn()} />);
    await fireEvent.changeText(screen.getByLabelText('Group name'), '  Kids  ');
    await fireEvent.press(screen.getByRole('radio', { name: 'Emoji 🦖' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Neon blue' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'PG' }));
    await fireEvent.press(screen.getByRole('radio', { name: 'Frightening & intense: Mild' }));
    await fireEvent.press(screen.getByRole('button', { name: 'Save changes' }));
    expect(update.mutate).toHaveBeenCalledWith(
      {
        groupId: 'g1',
        body: {
          name: 'Kids',
          emoji: '🦖',
          color: '#3DA5D9',
          comfortCap: { maxCertification: 'PG', contentLimits: { FRIGHTENING: 'MILD' } },
        },
      },
      expect.objectContaining({ onSuccess: onClose }),
    );
  });

  it('requires a name', async () => {
    await render(<GroupSettingsSheet group={makeGroup()} visible onClose={jest.fn()} onDelete={jest.fn()} />);
    await fireEvent.changeText(screen.getByLabelText('Group name'), ' ');
    await fireEvent.press(screen.getByRole('button', { name: 'Save changes' }));
    expect(screen.getByText('Give your group a name.')).toBeOnTheScreen();
    expect(update.mutate).not.toHaveBeenCalled();
  });

  it('shows server errors (field and general) and offers delete', async () => {
    const onDelete = jest.fn();
    update.isError = true;
    update.error = new ApiError(400, 'Validation failed', { name: 'Name is taken' });
    const { rerender } = await render(
      <GroupSettingsSheet group={makeGroup()} visible onClose={jest.fn()} onDelete={onDelete} />,
    );
    expect(screen.getByText('Name is taken')).toBeOnTheScreen();
    update.error = new ApiError(403, 'Only the owner can change this group');
    await rerender(<GroupSettingsSheet group={makeGroup()} visible onClose={jest.fn()} onDelete={onDelete} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Only the owner can change this group');
    await fireEvent.press(screen.getByRole('button', { name: 'Delete group' }));
    expect(onDelete).toHaveBeenCalled();
  });

  it('starts from the current cap', async () => {
    await render(
      <GroupSettingsSheet
        group={makeGroup({ comfortCap: { maxCertification: 'G', contentLimits: { LANGUAGE: 'NONE' } } })}
        visible
        onClose={jest.fn()}
        onDelete={jest.fn()}
      />,
    );
    expect(screen.getByRole('radio', { name: 'G' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Language: None' })).toBeChecked();
  });
});

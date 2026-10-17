jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/social', () => ({
  useFriendRequests: jest.fn(),
  useAcceptRequest: jest.fn(),
  useDeclineRequest: jest.fn(),
  useCancelRequest: jest.fn(),
  useSuggestions: jest.fn(),
  useSendFriendRequest: jest.fn(),
  useFriends: jest.fn(),
  useSetCloseFriend: jest.fn(),
}));
jest.mock('../api/users', () => ({ ...jest.requireActual('../api/users'), useUserSearch: jest.fn() }));
jest.mock('../api/invites', () => ({ useCreateInvite: jest.fn() }));
jest.mock('../api/feed', () => ({ useFeed: jest.fn(), useLikeRating: jest.fn(), useUnlikeRating: jest.fn() }));
const mockOpenRatePicker = jest.fn();
const mockOpenQuickRate = jest.fn();
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: mockOpenQuickRate, openRatePicker: mockOpenRatePicker, showTasteUpdating: jest.fn() }),
}));
jest.mock('../components/social/shareInvite', () => ({ shareInvite: jest.fn(() => Promise.resolve('copied')) }));

import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react-native';
import SocialScreen from '../app/(tabs)/index';
import { useFeed, useLikeRating, useUnlikeRating } from '../api/feed';
import { useCreateInvite } from '../api/invites';
import {
  useAcceptRequest,
  useCancelRequest,
  useDeclineRequest,
  useFriendRequests,
  useFriends,
  useSendFriendRequest,
  useSetCloseFriend,
  useSuggestions,
} from '../api/social';
import { useUserSearch } from '../api/users';
import { shareInvite } from '../components/social/shareInvite';
import { ApiError, NetworkError } from '../lib/apiClient';
import { makeFeedItem, makeFriend, makeMovie, makeRequest, makeSuggestion, makeUser } from '../test-utils/fixtures';
import { infiniteResult, mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';
import type { FeedScope, RequestDirection } from '../types/api';

const m = {
  requests: useFriendRequests as jest.Mock,
  accept: useAcceptRequest as jest.Mock,
  decline: useDeclineRequest as jest.Mock,
  cancel: useCancelRequest as jest.Mock,
  suggestions: useSuggestions as jest.Mock,
  send: useSendFriendRequest as jest.Mock,
  friends: useFriends as jest.Mock,
  setClose: useSetCloseFriend as jest.Mock,
  search: useUserSearch as jest.Mock,
  create: useCreateInvite as jest.Mock,
  feed: useFeed as jest.Mock,
};
let feeds: Record<FeedScope, ReturnType<typeof infiniteResult>>;
let like: ReturnType<typeof mutationResult>;
let unlike: ReturnType<typeof mutationResult>;

let incoming: ReturnType<typeof queryResult>;
let outgoing: ReturnType<typeof queryResult>;
let mutations: Record<
  'accept' | 'decline' | 'cancel' | 'send' | 'setClose' | 'create',
  ReturnType<typeof mutationResult>
>;

beforeEach(() => {
  jest.useFakeTimers();
  routerState.reset();
  mockOpenRatePicker.mockClear();
  mockOpenQuickRate.mockClear();
  feeds = { FRIENDS: infiniteResult({ items: [] }), EVERYONE: infiniteResult({ items: [] }) };
  m.feed.mockImplementation((scope: FeedScope) => feeds[scope]);
  like = mutationResult();
  unlike = mutationResult();
  (useLikeRating as jest.Mock).mockImplementation(() => like);
  (useUnlikeRating as jest.Mock).mockImplementation(() => unlike);
  incoming = queryResult({ data: [makeRequest({ id: 'r1', displayName: 'Rita Moreno', username: 'rita' })] });
  outgoing = queryResult({
    data: [makeRequest({ id: 'o1', displayName: 'Omar Sy', username: 'omar' }, 'OUTGOING')],
  });
  m.requests.mockImplementation((d: RequestDirection) => (d === 'INCOMING' ? incoming : outgoing));
  m.suggestions.mockReturnValue(
    queryResult({
      data: [
        makeSuggestion({ id: 's1', displayName: 'Sam Lee', username: 'sam' }, 3),
        makeSuggestion({ id: 's2', displayName: 'Kim Park', username: 'kim' }, 1),
      ],
    }),
  );
  m.friends.mockReturnValue(
    infiniteResult({
      items: [
        makeFriend({ id: 'f1', displayName: 'Ada Close', username: 'adac', closeFriend: true }),
        makeFriend({ id: 'f2', displayName: 'Bob Plain', username: 'bob' }),
      ],
    }),
  );
  m.search.mockImplementation((q: string) =>
    q.length >= 2
      ? infiniteResult({
          items: [
            makeUser({ id: 'x1', displayName: 'Grace Hopper', username: 'grace', friendship: 'NONE' }),
            makeUser({ id: 'x2', displayName: 'Greta Gerwig', username: 'greta', friendship: 'FRIENDS' }),
            makeUser({ id: 'x3', displayName: 'Greg', username: 'greg', friendship: 'REQUEST_SENT' }),
            makeUser({ id: 'x4', displayName: 'Gru', username: 'gru', friendship: 'REQUEST_RECEIVED' }),
          ],
        })
      : infiniteResult({ fetchStatus: 'idle' }),
  );
  mutations = {
    accept: mutationResult(),
    decline: mutationResult(),
    cancel: mutationResult(),
    send: mutationResult(),
    setClose: mutationResult(),
    create: mutationResult({
      mutateAsync: jest.fn(() => Promise.resolve({ token: 't', url: 'https://ff.app/invite/t', expiresAt: 'x' })),
    }),
  };
  (Object.keys(mutations) as (keyof typeof mutations)[]).forEach((k) => m[k].mockImplementation(() => mutations[k]));
});

afterEach(() => {
  jest.useRealTimers();
});

const openFriends = async () => {
  await fireEvent.press(screen.getByRole('tab', { name: /^Friends/ }));
};
const typeSearch = async (text: string) => {
  await fireEvent.changeText(screen.getByLabelText('Search people'), text);
  await act(async () => {
    jest.advanceTimersByTime(300);
  });
};

const friendItems = [
  makeFeedItem({ id: 'f1', author: makeUser({ id: 'a1', displayName: 'Rita Moreno' }), body: 'So tense', likeCount: 2 }),
  makeFeedItem({ id: 'f2', mine: true, author: makeUser({ id: 'u1' }), movie: makeMovie({ id: 200, title: 'Up' }) }),
];
const scopeRadio = (name: 'Friends' | 'Everyone') =>
  within(screen.getByLabelText('Feed audience')).getByRole('radio', { name });

describe('SocialScreen — feed', () => {
  it('starts on the Feed segment showing the FRIENDS feed', async () => {
    feeds.FRIENDS = infiniteResult({ items: friendItems });
    await render(<SocialScreen />);
    expect(screen.getByRole('header', { name: 'Social' })).toBeOnTheScreen();
    expect(screen.getByRole('tab', { name: 'Feed' })).toBeSelected();
    expect(scopeRadio('Friends')).toBeChecked();
    expect(m.feed).toHaveBeenLastCalledWith('FRIENDS');
    expect(screen.getByTestId('feed-card-f1')).toBeOnTheScreen();
    expect(screen.getByTestId('feed-card-f2')).toBeOnTheScreen();
    expect(screen.queryByTestId('friends-panel')).toBeNull();
  });

  it('switches to the EVERYONE feed and back', async () => {
    feeds.EVERYONE = infiniteResult({ items: [makeFeedItem({ id: 'p1', visibility: 'PUBLIC' })] });
    await render(<SocialScreen />);
    await fireEvent.press(scopeRadio('Everyone'));
    expect(m.feed).toHaveBeenLastCalledWith('EVERYONE');
    expect(scopeRadio('Everyone')).toBeChecked();
    expect(screen.getByTestId('feed-list-everyone')).toBeOnTheScreen();
    expect(screen.getByTestId('feed-card-p1')).toBeOnTheScreen();
    await fireEvent.press(scopeRadio('Friends'));
    expect(m.feed).toHaveBeenLastCalledWith('FRIENDS');
    expect(screen.getByTestId('feed-list-friends')).toBeOnTheScreen();
  });

  it('Friends empty state: "Rate a movie" opens the picker, "Find friends" switches segment', async () => {
    await render(<SocialScreen />);
    expect(screen.getByTestId('feed-empty-friends')).toBeOnTheScreen();
    expect(screen.getByText('Rate a movie or add friends to fill your feed')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Rate a movie' }));
    expect(mockOpenRatePicker).toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Find friends' }));
    expect(screen.getByRole('tab', { name: /^Friends/ })).toBeSelected();
    expect(screen.getByTestId('friends-panel')).toBeOnTheScreen();
  });

  it('Everyone empty state', async () => {
    await render(<SocialScreen />);
    await fireEvent.press(scopeRadio('Everyone'));
    expect(screen.getByTestId('feed-empty-everyone')).toBeOnTheScreen();
    expect(screen.getByText('No public ratings yet')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Rate a movie' })).toBeNull();
  });

  it.each<FeedScope>(['FRIENDS', 'EVERYONE'])('%s: skeletons while loading, then an error with retry', async (scope) => {
    feeds[scope] = infiniteResult();
    const { rerender } = await render(<SocialScreen />);
    if (scope === 'EVERYONE') await fireEvent.press(scopeRadio('Everyone'));
    const id = `feed-list-${scope.toLowerCase()}`;
    expect(screen.getByTestId(`${id}-loading`)).toBeOnTheScreen();

    const refetch = jest.fn();
    feeds[scope] = infiniteResult({ isError: true, isPending: false, error: new NetworkError(), refetch });
    await rerender(<SocialScreen />);
    expect(screen.getByText("Couldn't load your feed")).toBeOnTheScreen();
    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn't reach FilmFuse/);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('pull-to-refresh refetches and shows the spinner only for a user refresh', async () => {
    const refetch = jest.fn();
    feeds.FRIENDS = infiniteResult({ items: friendItems, refetch });
    const { rerender } = await render(<SocialScreen />);
    expect(screen.getByTestId('feed-list-friends')).toHaveProp('refreshing', false);
    await fireEvent(screen.getByTestId('feed-list-friends'), 'refresh');
    expect(refetch).toHaveBeenCalled();

    feeds.FRIENDS = infiniteResult({ items: friendItems, refetch, isRefetching: true });
    await rerender(<SocialScreen />);
    expect(screen.getByTestId('feed-list-friends')).toHaveProp('refreshing', true);
    feeds.FRIENDS = infiniteResult({ items: friendItems, refetch, isRefetching: true, isFetchingNextPage: true });
    await rerender(<SocialScreen />);
    expect(screen.getByTestId('feed-list-friends')).toHaveProp('refreshing', false);
  });

  it('loads the next page at the end, once, with a spinner and a later-page error', async () => {
    const fetchNextPage = jest.fn();
    feeds.FRIENDS = infiniteResult({ items: friendItems, hasNextPage: true, fetchNextPage });
    const { rerender } = await render(<SocialScreen />);
    await fireEvent(screen.getByTestId('feed-list-friends'), 'endReached');
    expect(fetchNextPage).toHaveBeenCalledTimes(1);

    feeds.FRIENDS = infiniteResult({ items: friendItems, hasNextPage: true, isFetchingNextPage: true, fetchNextPage });
    await rerender(<SocialScreen />);
    expect(screen.getByTestId('feed-list-friends-loading-more')).toBeOnTheScreen();
    await fireEvent(screen.getByTestId('feed-list-friends'), 'endReached');
    expect(fetchNextPage).toHaveBeenCalledTimes(1);

    feeds.FRIENDS = infiniteResult({ items: friendItems, hasNextPage: false, fetchNextPage });
    await rerender(<SocialScreen />);
    await fireEvent(screen.getByTestId('feed-list-friends'), 'endReached');
    expect(fetchNextPage).toHaveBeenCalledTimes(1);

    feeds.FRIENDS = infiniteResult({
      items: friendItems,
      hasNextPage: true,
      isError: true,
      isPending: false,
      error: new NetworkError(),
      fetchNextPage,
    });
    await rerender(<SocialScreen />);
    expect(screen.getByTestId('feed-card-f1')).toBeOnTheScreen();
    expect(screen.getByText("Couldn't load more")).toBeOnTheScreen();
    await fireEvent(screen.getByTestId('feed-list-friends'), 'endReached');
    expect(fetchNextPage).toHaveBeenCalledTimes(1);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(fetchNextPage).toHaveBeenCalledTimes(2);
  });

  it('cards in the feed like, edit and navigate', async () => {
    feeds.FRIENDS = infiniteResult({ items: friendItems });
    await render(<SocialScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Like rating, 2 likes' }));
    expect(like.mutate).toHaveBeenCalledWith('f1');
    await fireEvent.press(screen.getByRole('button', { name: 'Edit your rating of Up' }));
    expect(mockOpenQuickRate).toHaveBeenCalledWith(expect.objectContaining({ id: 200 }));
    await fireEvent.press(screen.getByRole('button', { name: /^Rita Moreno/ }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/a1');
  });

  it('shows the incoming-request count on the Friends segment', async () => {
    await render(<SocialScreen />);
    expect(screen.getByRole('tab', { name: 'Friends, 1 new' })).toBeOnTheScreen();
    expect(screen.getByTestId('segment-badge-friends')).toHaveTextContent('1');
  });

  it('has no badge without incoming requests', async () => {
    incoming = queryResult({ data: [] });
    await render(<SocialScreen />);
    expect(screen.getByRole('tab', { name: 'Friends' })).toBeOnTheScreen();
    expect(screen.queryByTestId('segment-badge-friends')).toBeNull();
  });
});

describe('SocialScreen — friends segment', () => {
  it('shows every section', async () => {
    await render(<SocialScreen />);
    await openFriends();
    const sections = ['section-incoming', 'section-suggestions', 'section-friends', 'section-outgoing'].map((id) =>
      screen.getByTestId(id),
    );
    sections.forEach((s) => expect(s).toBeOnTheScreen());
    expect(screen.getByText('Friend requests · 1')).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'Invite friends' })).toBeOnTheScreen();
  });

  it('incoming: Accept and Decline call their mutations', async () => {
    await render(<SocialScreen />);
    await openFriends();
    await fireEvent.press(screen.getByRole('button', { name: 'Accept friend request from Rita Moreno' }));
    expect(mutations.accept.mutate).toHaveBeenCalledWith('r1');
    await fireEvent.press(screen.getByRole('button', { name: 'Decline friend request from Rita Moreno' }));
    expect(mutations.decline.mutate).toHaveBeenCalledWith('r1');
  });

  it('incoming: the busy row disables both buttons', async () => {
    mutations.accept.isPending = true;
    mutations.accept.variables = 'r1';
    await render(<SocialScreen />);
    await openFriends();
    expect(screen.getByRole('button', { name: 'Accept friend request from Rita Moreno' })).toBeBusy();
    expect(screen.getByRole('button', { name: 'Decline friend request from Rita Moreno' })).toBeDisabled();
  });

  it('incoming: shows a failed action', async () => {
    mutations.decline.isError = true;
    mutations.decline.error = new ApiError(404, 'No such request');
    await render(<SocialScreen />);
    await openFriends();
    expect(within(screen.getByTestId('section-incoming')).getByRole('alert')).toHaveTextContent('No such request');
  });

  it('incoming: loading, error with retry, and hidden when empty', async () => {
    incoming = queryResult();
    const { rerender } = await render(<SocialScreen />);
    await openFriends();
    expect(screen.getByTestId('incoming-loading')).toBeOnTheScreen();

    const refetch = jest.fn();
    incoming = queryResult({ isError: true, isPending: false, error: new NetworkError(), refetch });
    await rerender(<SocialScreen />);
    expect(screen.getByText("Couldn't load requests")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();

    incoming = queryResult({ data: [] });
    await rerender(<SocialScreen />);
    expect(screen.queryByTestId('section-incoming')).toBeNull();
  });

  it('suggestions: mutual-friend counts, Add, and opening a profile', async () => {
    await render(<SocialScreen />);
    await openFriends();
    expect(screen.getByText('3 mutual friends')).toBeOnTheScreen();
    expect(screen.getByText('1 mutual friend')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Add Sam Lee as a friend' }));
    expect(mutations.send.mutate).toHaveBeenCalledWith('s1');
    await fireEvent.press(screen.getByRole('button', { name: 'Sam Lee, @sam' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/s1');
  });

  it('suggestions: shows a send error inline', async () => {
    mutations.send.isError = true;
    mutations.send.error = new ApiError(409, 'Already friends');
    await render(<SocialScreen />);
    await openFriends();
    expect(within(screen.getByTestId('section-suggestions')).getByRole('alert')).toHaveTextContent('Already friends');
  });

  it('suggestions: loading, then hidden when empty or failed', async () => {
    m.suggestions.mockReturnValue(queryResult());
    const { rerender } = await render(<SocialScreen />);
    await openFriends();
    expect(screen.getByTestId('suggestions-loading')).toBeOnTheScreen();
    m.suggestions.mockReturnValue(queryResult({ data: [] }));
    await rerender(<SocialScreen />);
    expect(screen.queryByTestId('section-suggestions')).toBeNull();
    m.suggestions.mockReturnValue(queryResult({ isError: true, isPending: false, error: new NetworkError() }));
    await rerender(<SocialScreen />);
    expect(screen.queryByTestId('section-suggestions')).toBeNull();
  });

  it('friends: star toggles close friend, row opens the profile', async () => {
    await render(<SocialScreen />);
    await openFriends();
    expect(screen.getByRole('switch', { name: 'Close friend: Ada Close' })).toBeChecked();
    expect(screen.getByText('Close friend')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('switch', { name: 'Close friend: Bob Plain' }));
    expect(mutations.setClose.mutate).toHaveBeenCalledWith({ userId: 'f2', closeFriend: true });
    await fireEvent.press(screen.getByRole('switch', { name: 'Close friend: Ada Close' }));
    expect(mutations.setClose.mutate).toHaveBeenCalledWith({ userId: 'f1', closeFriend: false });
    await fireEvent.press(screen.getByRole('button', { name: 'Bob Plain, @bob' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/f2');
  });

  it('friends: someone without a username renders without "@null"', async () => {
    m.friends.mockReturnValue(
      infiniteResult({
        items: [
          makeFriend({ id: 'n1', displayName: 'Invited Ivy', username: null }),
          makeFriend({ id: 'n2', displayName: null, username: null }),
        ],
      }),
    );
    await render(<SocialScreen />);
    await openFriends();
    const section = within(screen.getByTestId('section-friends'));
    expect(section.getByRole('button', { name: 'Invited Ivy' })).toBeOnTheScreen();
    expect(section.getByRole('button', { name: 'New member' })).toBeOnTheScreen();
    expect(section.getByRole('switch', { name: 'Close friend: New member' })).toBeOnTheScreen();
    expect(section.queryByText(/@|null/)).toBeNull();
    await fireEvent.press(section.getByRole('button', { name: 'New member' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/n2');
  });

  it('friends: shows a close-friend failure', async () => {
    mutations.setClose.isError = true;
    mutations.setClose.error = new ApiError(404, 'Not friends');
    await render(<SocialScreen />);
    await openFriends();
    expect(within(screen.getByTestId('section-friends')).getByRole('alert')).toHaveTextContent('Not friends');
  });

  it('friends: loading, error with retry, and empty', async () => {
    m.friends.mockReturnValue(infiniteResult());
    const { rerender } = await render(<SocialScreen />);
    await openFriends();
    expect(screen.getByTestId('friends-loading')).toBeOnTheScreen();

    const refetch = jest.fn();
    m.friends.mockReturnValue(infiniteResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await rerender(<SocialScreen />);
    expect(screen.getByText("Couldn't load your friends")).toBeOnTheScreen();
    await fireEvent.press(within(screen.getByTestId('section-friends')).getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();

    m.friends.mockReturnValue(infiniteResult({ items: [] }));
    await rerender(<SocialScreen />);
    expect(screen.getByTestId('friends-empty')).toHaveTextContent(/No friends yet/);
  });

  it('friends: "Show more friends" loads the next page', async () => {
    const fetchNextPage = jest.fn();
    m.friends.mockReturnValue(infiniteResult({ items: [makeFriend({ id: 'f1' })], hasNextPage: true, fetchNextPage }));
    await render(<SocialScreen />);
    await openFriends();
    await fireEvent.press(screen.getByRole('button', { name: 'Show more friends' }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('friends: a failed later page keeps the list and shows the error', async () => {
    m.friends.mockReturnValue(
      infiniteResult({ items: [makeFriend({ id: 'f1' })], isError: true, error: new NetworkError(), isPending: false }),
    );
    await render(<SocialScreen />);
    await openFriends();
    expect(screen.getByRole('button', { name: 'Grace Hopper, @grace' })).toBeOnTheScreen();
    expect(within(screen.getByTestId('section-friends')).getByRole('alert')).toHaveTextContent(/Couldn't reach/);
  });

  it('outgoing: Cancel withdraws; hidden while loading or empty', async () => {
    const { rerender } = await render(<SocialScreen />);
    await openFriends();
    expect(screen.getByText('Waiting for them to accept')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Cancel friend request to Omar Sy' }));
    expect(mutations.cancel.mutate).toHaveBeenCalledWith('o1');
    outgoing = queryResult();
    await rerender(<SocialScreen />);
    expect(screen.queryByTestId('section-outgoing')).toBeNull();
    outgoing = queryResult({ data: [] });
    await rerender(<SocialScreen />);
    expect(screen.queryByTestId('section-outgoing')).toBeNull();
  });

  it('outgoing: shows a cancel failure', async () => {
    mutations.cancel.isError = true;
    mutations.cancel.error = new NetworkError();
    await render(<SocialScreen />);
    await openFriends();
    expect(within(screen.getByTestId('section-outgoing')).getByRole('alert')).toBeOnTheScreen();
  });

  it('invite card shares a new link and confirms a copy', async () => {
    await render(<SocialScreen />);
    await openFriends();
    await fireEvent.press(screen.getByRole('button', { name: 'Share invite link' }));
    await waitFor(() => expect(shareInvite).toHaveBeenCalledWith('https://ff.app/invite/t'));
    expect(await screen.findByText(/Link copied/)).toBeOnTheScreen();
  });

  it('the requests row opens the requester profile', async () => {
    await render(<SocialScreen />);
    await openFriends();
    await fireEvent.press(screen.getByRole('button', { name: 'Rita Moreno, @rita' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/r1');
  });
});

describe('SocialScreen — search', () => {
  it('debounces, then shows results with state-aware actions', async () => {
    await render(<SocialScreen />);
    await fireEvent.changeText(screen.getByLabelText('Search people'), 'gr');
    expect(m.search).not.toHaveBeenCalledWith('gr');
    await act(async () => {
      jest.advanceTimersByTime(300);
    });
    expect(m.search).toHaveBeenLastCalledWith('gr');
    expect(screen.getByText('People matching “gr”')).toBeOnTheScreen();
    expect(screen.queryByRole('tab', { name: 'Feed' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Add Grace Hopper as a friend' })).toBeOnTheScreen();
    expect(screen.getByLabelText('You and Greta Gerwig are friends')).toBeOnTheScreen();
    expect(screen.getByLabelText('Friend request sent to Greg')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Accept friend request from Gru' }));
    expect(mutations.accept.mutate).toHaveBeenCalledWith('x4');
    await fireEvent.press(screen.getByRole('button', { name: 'Add Grace Hopper as a friend' }));
    expect(mutations.send.mutate).toHaveBeenCalledWith('x1');
  });

  it('opens a profile from a result', async () => {
    await render(<SocialScreen />);
    await typeSearch('gr');
    await fireEvent.press(screen.getByRole('button', { name: 'Greta Gerwig, @greta' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/user/x2');
  });

  it('asks for two characters first', async () => {
    await render(<SocialScreen />);
    await typeSearch('g');
    expect(screen.getByTestId('search-hint')).toHaveTextContent(/at least 2 characters/);
  });

  it('shows loading, empty and error states', async () => {
    m.search.mockReturnValue(infiniteResult());
    const { rerender } = await render(<SocialScreen />);
    await typeSearch('zz');
    expect(screen.getByTestId('search-loading')).toBeOnTheScreen();

    m.search.mockReturnValue(infiniteResult({ items: [] }));
    await rerender(<SocialScreen />);
    expect(screen.getByText('No one found')).toBeOnTheScreen();

    const refetch = jest.fn();
    m.search.mockReturnValue(infiniteResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await rerender(<SocialScreen />);
    expect(screen.getByText('Search failed')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('pages at the end of the list, shows a spinner and a later-page error', async () => {
    const fetchNextPage = jest.fn();
    m.search.mockReturnValue(infiniteResult({ items: [makeUser({ id: 'x1' })], hasNextPage: true, fetchNextPage }));
    const { rerender } = await render(<SocialScreen />);
    await typeSearch('gr');
    await fireEvent(screen.getByTestId('user-search-results'), 'endReached');
    expect(fetchNextPage).toHaveBeenCalledTimes(1);

    m.search.mockReturnValue(
      infiniteResult({ items: [makeUser({ id: 'x1' })], hasNextPage: true, isFetchingNextPage: true, fetchNextPage }),
    );
    await rerender(<SocialScreen />);
    expect(screen.getByTestId('search-loading-more')).toBeOnTheScreen();
    await fireEvent(screen.getByTestId('user-search-results'), 'endReached');
    expect(fetchNextPage).toHaveBeenCalledTimes(1);

    m.search.mockReturnValue(
      infiniteResult({
        items: [makeUser({ id: 'x1' })],
        isError: true,
        error: new NetworkError(),
        isPending: false,
        fetchNextPage,
      }),
    );
    await rerender(<SocialScreen />);
    expect(screen.getByText("Couldn't load more")).toBeOnTheScreen();
  });

  it('clearing the search returns to the segments', async () => {
    await render(<SocialScreen />);
    await typeSearch('gr');
    await fireEvent.press(screen.getByRole('button', { name: 'Clear search' }));
    expect(screen.getByRole('tab', { name: 'Feed' })).toBeOnTheScreen();
  });
});

jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/feed', () => ({ useLikeRating: jest.fn(), useUnlikeRating: jest.fn() }));
const mockOpenQuickRate = jest.fn();
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: mockOpenQuickRate, openRatePicker: jest.fn(), showTasteUpdating: jest.fn() }),
}));

import { fireEvent, render, screen } from '@testing-library/react-native';
import { useLikeRating, useUnlikeRating } from '../api/feed';
import { FeedCard, FeedCardSkeletons } from '../components/feed/FeedCard';
import { makeFeedItem, makeMovie, makeUser } from '../test-utils/fixtures';
import { mutationResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

let like: ReturnType<typeof mutationResult>;
let unlike: ReturnType<typeof mutationResult>;

beforeEach(() => {
  jest.useFakeTimers({ now: new Date('2026-10-10T23:00:00Z') });
  routerState.reset();
  mockOpenQuickRate.mockClear();
  like = mutationResult();
  unlike = mutationResult();
  (useLikeRating as jest.Mock).mockReturnValue(like);
  (useUnlikeRating as jest.Mock).mockReturnValue(unlike);
});
afterEach(() => jest.useRealTimers());

const heat = makeMovie({ id: 100, title: 'Heat', releaseYear: 1995, posterUrl: 'https://img/heat.jpg' });
const grace = makeUser({ id: 'u2', displayName: 'Grace Hopper', username: 'grace', friendship: 'FRIENDS' });
const item = (overrides = {}) => makeFeedItem({ id: 'f1', author: grace, movie: heat, score: 8, ...overrides });

describe('FeedCard — content', () => {
  it('shows author, time ago, movie, year, certification and the score with its words', async () => {
    await render(<FeedCard item={item()} />);
    expect(screen.getByText('Grace Hopper')).toBeOnTheScreen();
    expect(screen.getByText('3 hours ago')).toBeOnTheScreen();
    expect(screen.getByText('Heat')).toBeOnTheScreen();
    expect(screen.getByText('1995')).toBeOnTheScreen();
    expect(screen.getByLabelText('Rated R')).toBeOnTheScreen();
    expect(screen.getByText('8/10')).toBeOnTheScreen();
    expect(screen.getByText('really like')).toBeOnTheScreen();
    expect(screen.getByLabelText("Grace Hopper's profile photo")).toBeOnTheScreen();
  });

  it('labels an unknown certification and copes with a missing year and poster', async () => {
    await render(
      <FeedCard
        item={item({ certification: null, movie: makeMovie({ title: 'Mystery', releaseYear: null, posterUrl: null }) })}
      />,
    );
    expect(screen.getByLabelText('Not rated')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Mystery' })).toBeOnTheScreen();
    expect(screen.getByTestId('icon-film-outline')).toBeOnTheScreen();
  });

  it('uses the compact layout without a note', async () => {
    await render(<FeedCard item={item({ body: null })} />);
    expect(screen.getByTestId('feed-poster-compact')).toBeOnTheScreen();
    expect(screen.queryByTestId('feed-poster')).toBeNull();
    expect(screen.queryByTestId('feed-note-f1')).toBeNull();
  });

  it('shows a note in the full layout', async () => {
    await render(<FeedCard item={item({ body: 'Best shootout ever.' })} />);
    expect(screen.getByTestId('feed-poster')).toBeOnTheScreen();
    expect(screen.getByTestId('feed-note-f1')).toHaveTextContent('Best shootout ever.');
    expect(screen.queryByTestId('feed-spoiler-f1')).toBeNull();
  });

  it('covers a spoiler note until tapped, without rendering the hidden text', async () => {
    await render(<FeedCard item={item({ body: 'Neil dies.', containsSpoilers: true })} />);
    expect(screen.queryByText('Neil dies.')).toBeNull();
    const cover = screen.getByRole('button', { name: 'Note hidden: contains spoilers' });
    expect(cover.props.accessibilityHint).toBe('Reveals the note');
    expect(screen.getByText('Contains spoilers · Tap to reveal')).toBeOnTheScreen();
    await fireEvent.press(cover);
    expect(screen.getByTestId('feed-note-f1')).toHaveTextContent('Neil dies.');
    expect(screen.queryByTestId('feed-spoiler-f1')).toBeNull();
  });

  it('a spoiler flag without a note shows nothing', async () => {
    await render(<FeedCard item={item({ body: null, containsSpoilers: true })} />);
    expect(screen.queryByTestId('feed-spoiler-f1')).toBeNull();
  });

  it('accessible score label', async () => {
    await render(<FeedCard item={item({ score: 10 })} />);
    expect(screen.getByLabelText('Rated 10 out of 10, all-time')).toBeOnTheScreen();
  });
});

describe('FeedCard — navigation', () => {
  it('author opens their profile; the movie opens its page', async () => {
    await render(<FeedCard item={item()} />);
    const author = screen.getByRole('button', { name: 'Grace Hopper, 3 hours ago' });
    expect(author.props.accessibilityHint).toBe('Opens their profile');
    await fireEvent.press(author);
    expect(routerState.router.push).toHaveBeenCalledWith('/user/u2');
    const movie = screen.getByRole('button', { name: 'Heat, 1995' });
    expect(movie.props.accessibilityHint).toBe('Opens the movie');
    await fireEvent.press(movie);
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/100');
  });

  it('showAuthor=false hides the header but keeps the time; showMovie=false hides the movie row', async () => {
    await render(<FeedCard item={item()} showAuthor={false} showMovie={false} />);
    expect(screen.queryByRole('button', { name: /^Grace Hopper/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Heat/ })).toBeNull();
    expect(screen.getByText('3 hours ago')).toBeOnTheScreen();
    expect(screen.getByText('8/10')).toBeOnTheScreen();
  });

  it('falls back to @username / New member for the author', async () => {
    await render(<FeedCard item={item({ author: makeUser({ id: 'x', displayName: null, username: 'bob' }) })} />);
    expect(screen.getByText('@bob')).toBeOnTheScreen();
    await render(<FeedCard item={item({ author: makeUser({ id: 'y', displayName: null, username: null }) })} />);
    expect(screen.getByText('New member')).toBeOnTheScreen();
  });
});

describe('FeedCard — likes', () => {
  it('likes an unliked rating', async () => {
    await render(<FeedCard item={item({ likeCount: 3, likedByMe: false })} />);
    const heart = screen.getByRole('button', { name: 'Like rating, 3 likes' });
    expect(heart).not.toBeSelected();
    expect(screen.getByTestId('icon-heart-outline')).toBeOnTheScreen();
    expect(screen.getByTestId('feed-like-count-f1')).toHaveTextContent('3');
    await fireEvent.press(heart);
    expect(like.mutate).toHaveBeenCalledWith('f1');
    expect(unlike.mutate).not.toHaveBeenCalled();
  });

  it('unlikes a liked rating', async () => {
    await render(<FeedCard item={item({ likeCount: 1, likedByMe: true })} />);
    const heart = screen.getByRole('button', { name: 'Unlike rating, 1 like' });
    expect(heart).toBeSelected();
    expect(screen.getByTestId('icon-heart-filled')).toBeOnTheScreen();
    await fireEvent.press(heart);
    expect(unlike.mutate).toHaveBeenCalledWith('f1');
    expect(like.mutate).not.toHaveBeenCalled();
  });

  it('follows the item as the cache updates (optimistic)', async () => {
    const { rerender } = await render(<FeedCard item={item({ likeCount: 0 })} />);
    expect(screen.getByRole('button', { name: 'Like rating, 0 likes' })).toBeOnTheScreen();
    await rerender(<FeedCard item={item({ likeCount: 1, likedByMe: true })} />);
    expect(screen.getByRole('button', { name: 'Unlike rating, 1 like' })).toBeSelected();
    expect(screen.getByTestId('feed-like-count-f1')).toHaveTextContent('1');
  });
});

describe('FeedCard — own vs others', () => {
  it("someone else's rating has no visibility or Edit", async () => {
    await render(<FeedCard item={item({ mine: false })} />);
    expect(screen.queryByTestId('feed-visibility-f1')).toBeNull();
    expect(screen.queryByRole('button', { name: /^Edit/ })).toBeNull();
  });

  it.each([
    ['PRIVATE', 'Only you', 'icon-lock-closed-outline'],
    ['FRIENDS', 'Friends', 'icon-people-outline'],
    ['PUBLIC', 'Public', 'icon-globe-outline'],
  ] as const)('your own %s rating shows "%s"', async (visibility, label, icon) => {
    await render(<FeedCard item={item({ mine: true, visibility })} />);
    expect(screen.getByLabelText(`Visible to: ${label}`)).toBeOnTheScreen();
    expect(screen.getByTestId(icon)).toBeOnTheScreen();
    expect(screen.getByText('You')).toBeOnTheScreen();
  });

  it('Edit reopens the quick-rate sheet on that movie', async () => {
    await render(<FeedCard item={item({ mine: true })} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Edit your rating of Heat' }));
    expect(mockOpenQuickRate).toHaveBeenCalledWith(heat);
  });

  it('keeps visibility and Edit with the author header hidden', async () => {
    await render(<FeedCard item={item({ mine: true, visibility: 'PUBLIC' })} showAuthor={false} />);
    expect(screen.getByLabelText('Visible to: Public')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Edit your rating of Heat' })).toBeOnTheScreen();
  });
});

describe('FeedCardSkeletons', () => {
  it('renders the requested number of placeholders', async () => {
    await render(<FeedCardSkeletons count={2} testID="sk" />);
    expect(screen.getByTestId('sk').children).toHaveLength(2);
  });
});

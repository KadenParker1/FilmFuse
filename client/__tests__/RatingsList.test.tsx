jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/ratings', () => ({ useMyRatings: jest.fn() }));

import { fireEvent, render, screen } from '@testing-library/react-native';
import { useMyRatings } from '../api/ratings';
import { RatingsList } from '../components/rating/RatingsList';
import { NetworkError } from '../lib/apiClient';
import { makeMovie, makeRating } from '../test-utils/fixtures';
import { infiniteResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const mockRatings = useMyRatings as jest.Mock;
const heat = makeRating({ id: 'r1', movie: makeMovie({ id: 100, title: 'Heat' }), score: 9, body: 'Great heist', visibility: 'FRIENDS' });
const alien = makeRating({
  id: 'r2',
  movie: makeMovie({ id: 200, title: 'Alien', releaseYear: 1979 }),
  score: 3,
  body: 'The ending!',
  containsSpoilers: true,
  visibility: 'PRIVATE',
});

beforeEach(() => routerState.reset());

describe('RatingsList', () => {
  it('lists ratings with score, label, note (spoilers hidden) and visibility; tap opens the movie', async () => {
    mockRatings.mockReturnValue(infiniteResult({ items: [heat, alien] }));
    await render(<RatingsList />);
    expect(screen.getByRole('header', { name: 'Your ratings' })).toBeOnTheScreen();
    expect(screen.getByText('9/10')).toBeOnTheScreen();
    expect(screen.getByText('all-time · 1995')).toBeOnTheScreen();
    expect(screen.getByTestId('rating-note-r1')).toHaveTextContent('Great heist');
    expect(screen.getByTestId('rating-note-r2')).toHaveTextContent('Note hidden: contains spoilers');
    expect(screen.getByText('Friends')).toBeOnTheScreen();
    expect(screen.getByText('Only you')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Alien, you rated it 3 out of 10' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/200');
  });

  it('loads more with "Show more" while there are pages', async () => {
    const fetchNextPage = jest.fn();
    mockRatings.mockReturnValue(infiniteResult({ items: [heat], hasNextPage: true, fetchNextPage }));
    await render(<RatingsList />);
    await fireEvent.press(screen.getByRole('button', { name: 'Show more' }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('shows loading, empty and error states', async () => {
    mockRatings.mockReturnValue(infiniteResult());
    await render(<RatingsList />);
    expect(screen.getByTestId('ratings-loading')).toBeOnTheScreen();

    mockRatings.mockReturnValue(infiniteResult({ items: [] }));
    await render(<RatingsList />);
    expect(screen.getByTestId('ratings-empty')).toBeOnTheScreen();

    const refetch = jest.fn();
    mockRatings.mockReturnValue(infiniteResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await render(<RatingsList />);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('keeps loaded ratings when a later page fails, with a retry', async () => {
    const fetchNextPage = jest.fn();
    mockRatings.mockReturnValue(infiniteResult({ items: [heat], isError: true, error: new NetworkError(), fetchNextPage }));
    await render(<RatingsList />);
    expect(screen.getByText('Heat')).toBeOnTheScreen();
    expect(screen.getByText("Couldn't load more")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(fetchNextPage).toHaveBeenCalled();
  });
});

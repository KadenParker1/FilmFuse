jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/movies', () => ({ ...jest.requireActual('../api/movies'), useMovie: jest.fn() }));
jest.mock('../api/ratings', () => ({ ...jest.requireActual('../api/ratings'), useMyRating: jest.fn() }));
jest.mock('../api/feed', () => ({
  ...jest.requireActual('../api/feed'),
  useMovieRatings: jest.fn(),
  useLikeRating: jest.fn(),
  useUnlikeRating: jest.fn(),
}));
const mockOpenQuickRate = jest.fn();
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: mockOpenQuickRate, openRatePicker: jest.fn(), showTasteUpdating: jest.fn() }),
}));

import { fireEvent, render, screen, within } from '@testing-library/react-native';
import MovieScreen from '../app/movie/[id]';
import { useLikeRating, useMovieRatings, useUnlikeRating } from '../api/feed';
import { useMovie } from '../api/movies';
import { useMyRating } from '../api/ratings';
import { TMDB_ATTRIBUTION } from '../components/TmdbAttribution';
import { ApiError, NetworkError } from '../lib/apiClient';
import { formatRuntime } from '../lib/format';
import { makeFeedItem, makeRating, makeUser } from '../test-utils/fixtures';
import { infiniteResult, mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';
import type { MovieDetailDTO } from '../types/api';

const mockUseMovie = useMovie as jest.Mock;
const mockUseMyRating = useMyRating as jest.Mock;
const mockMovieRatings = useMovieRatings as jest.Mock;

const dune: MovieDetailDTO = {
  id: 438631,
  title: 'Dune',
  releaseYear: 2021,
  runtime: 155,
  overview: 'Paul Atreides travels to the most dangerous planet in the universe.',
  posterUrl: 'https://image.tmdb.org/t/p/w500/dune.jpg',
  backdropUrl: 'https://image.tmdb.org/t/p/w1280/dune-bd.jpg',
  genres: [
    { id: 878, name: 'Science Fiction' },
    { id: 12, name: 'Adventure' },
  ],
  voteAverage: 7.8,
  popularity: 300.5,
};

beforeEach(() => {
  routerState.reset();
  routerState.params = { id: '438631' };
  mockUseMovie.mockReturnValue(queryResult({ data: dune }));
  mockUseMyRating.mockReturnValue(queryResult({ data: null, isPending: false, isSuccess: true }));
  mockOpenQuickRate.mockClear();
  mockMovieRatings.mockReturnValue(infiniteResult({ items: [] }));
  (useLikeRating as jest.Mock).mockReturnValue(mutationResult());
  (useUnlikeRating as jest.Mock).mockReturnValue(mutationResult());
});

describe('formatRuntime', () => {
  it.each([
    [155, '2h 35m'],
    [120, '2h'],
    [45, '45m'],
    [0, null],
    [null, null],
  ])('%p → %p', (mins, out) => expect(formatRuntime(mins)).toBe(out));
});

describe('MovieScreen', () => {
  it('renders the movie details', async () => {
    await render(<MovieScreen />);
    expect(mockUseMovie).toHaveBeenCalledWith(438631);
    expect(screen.getByRole('header', { name: 'Dune' })).toBeOnTheScreen();
    expect(screen.getByText('2021 · 2h 35m · Science Fiction, Adventure')).toBeOnTheScreen();
    expect(screen.getByLabelText('TMDB rating 7.8 out of 10')).toBeOnTheScreen();
    expect(screen.getByText(dune.overview!)).toBeOnTheScreen();
    expect(screen.getByTestId('movie-backdrop')).toBeOnTheScreen();
    expect(screen.getByTestId('movie-poster')).toBeOnTheScreen();
    expect(screen.getByText(TMDB_ATTRIBUTION)).toBeOnTheScreen();
  });

  it('opens the quick-rate sheet on this movie from Rate; watchlist stays disabled', async () => {
    await render(<MovieScreen />);
    expect(mockUseMyRating).toHaveBeenCalledWith(438631);
    expect(screen.getByRole('button', { name: 'Add to watchlist' })).toBeDisabled();
    expect(screen.queryByTestId('my-rating')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Rate' }));
    expect(mockOpenQuickRate).toHaveBeenCalledWith({
      id: 438631,
      title: 'Dune',
      releaseYear: 2021,
      posterUrl: dune.posterUrl,
      genreIds: [878, 12],
      voteAverage: 7.8,
    });
  });

  it('shows "Your rating 8/10" and Edit rating once rated', async () => {
    mockUseMyRating.mockReturnValue(queryResult({ data: makeRating({ score: 8 }) }));
    await render(<MovieScreen />);
    expect(screen.getByTestId('my-rating')).toHaveTextContent('Your rating 8/10');
    await fireEvent.press(screen.getByRole('button', { name: 'Edit rating' }));
    expect(mockOpenQuickRate).toHaveBeenCalledTimes(1);
  });

  it('handles missing optional fields', async () => {
    mockUseMovie.mockReturnValue(
      queryResult({
        data: { ...dune, runtime: null, releaseYear: null, genres: [], voteAverage: null, overview: null, posterUrl: null, backdropUrl: null },
      }),
    );
    await render(<MovieScreen />);
    expect(screen.getByText('No overview available yet.')).toBeOnTheScreen();
    expect(screen.queryByLabelText(/TMDB rating/)).toBeNull();
    expect(screen.queryByTestId('movie-backdrop')).toBeNull();
  });

  it('shows a skeleton while loading', async () => {
    mockUseMovie.mockReturnValue(queryResult());
    await render(<MovieScreen />);
    expect(screen.getByTestId('movie-skeleton')).toBeOnTheScreen();
  });

  it('shows not found for a 404', async () => {
    mockUseMovie.mockReturnValue(queryResult({ isError: true, isPending: false, error: new ApiError(404, 'Movie not found') }));
    await render(<MovieScreen />);
    expect(screen.getByText('Movie not found')).toBeOnTheScreen();
  });

  it('shows not found for a non-numeric id', async () => {
    routerState.params = { id: 'abc' };
    mockUseMovie.mockReturnValue(queryResult());
    await render(<MovieScreen />);
    expect(screen.getByText('Movie not found')).toBeOnTheScreen();
  });

  it('shows an error with retry for other failures', async () => {
    const refetch = jest.fn();
    mockUseMovie.mockReturnValue(queryResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await render(<MovieScreen />);
    expect(screen.getByText("Couldn't load this movie")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('goes back when there is history, or to Discover after a deep link', async () => {
    await render(<MovieScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.back).toHaveBeenCalled();

    routerState.router.canGoBack.mockReturnValue(false);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/discover');
  });
});

describe('MovieScreen — Watch with…', () => {
  it('opens a new session with the movie as context', async () => {
    await render(<MovieScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Watch Dune with friends' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/sessions/new?movieId=438631');
  });
});

describe('MovieScreen — ratings section', () => {
  const items = [1, 2, 3, 4].map((n) =>
    makeFeedItem({
      id: `f${n}`,
      author: makeUser({ id: `a${n}`, displayName: `Friend ${n}`, username: `friend${n}` }),
      score: n + 5,
    }),
  );
  const section = () => within(screen.getByTestId('movie-ratings'));

  it("asks for this movie's FRIENDS ratings first and previews the first 3", async () => {
    mockMovieRatings.mockReturnValue(infiniteResult({ items }));
    await render(<MovieScreen />);
    expect(mockMovieRatings).toHaveBeenLastCalledWith(438631, 'FRIENDS');
    expect(section().getByRole('header', { name: 'Ratings' })).toBeOnTheScreen();
    expect(section().getByRole('radio', { name: 'Friends' })).toBeChecked();
    expect(screen.getByTestId('feed-card-f1')).toBeOnTheScreen();
    expect(screen.getByTestId('feed-card-f3')).toBeOnTheScreen();
    expect(screen.queryByTestId('feed-card-f4')).toBeNull();
    // The movie is the page itself, so cards skip the poster row.
    expect(screen.queryByRole('button', { name: /^Heat/ })).toBeNull();
  });

  it('"See all" opens the full list in the chosen scope', async () => {
    mockMovieRatings.mockReturnValue(infiniteResult({ items }));
    await render(<MovieScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'See all ratings of Dune' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/438631/ratings?scope=FRIENDS');

    await fireEvent.press(section().getByRole('radio', { name: 'Everyone' }));
    expect(mockMovieRatings).toHaveBeenLastCalledWith(438631, 'EVERYONE');
    expect(section().getByRole('radio', { name: 'Everyone' })).toBeChecked();
    await fireEvent.press(screen.getByRole('button', { name: 'See all ratings of Dune' }));
    expect(routerState.router.push).toHaveBeenLastCalledWith('/movie/438631/ratings?scope=EVERYONE');
  });

  it('shows "See all" when only the server has more pages, and hides it otherwise', async () => {
    mockMovieRatings.mockReturnValue(infiniteResult({ items: items.slice(0, 2), hasNextPage: true }));
    const { rerender } = await render(<MovieScreen />);
    expect(screen.getByRole('button', { name: 'See all ratings of Dune' })).toBeOnTheScreen();
    mockMovieRatings.mockReturnValue(infiniteResult({ items: items.slice(0, 3) }));
    await rerender(<MovieScreen />);
    expect(screen.queryByRole('button', { name: 'See all ratings of Dune' })).toBeNull();
  });

  it('empty copy depends on the scope', async () => {
    await render(<MovieScreen />);
    expect(screen.getByTestId('movie-ratings-empty')).toHaveTextContent('None of your friends have rated this yet.');
    await fireEvent.press(section().getByRole('radio', { name: 'Everyone' }));
    expect(screen.getByTestId('movie-ratings-empty')).toHaveTextContent('No public ratings of this movie yet.');
  });

  it('shows skeletons while loading and an error with retry', async () => {
    mockMovieRatings.mockReturnValue(infiniteResult());
    const { rerender } = await render(<MovieScreen />);
    expect(screen.getByTestId('movie-ratings-loading')).toBeOnTheScreen();

    const refetch = jest.fn();
    mockMovieRatings.mockReturnValue(infiniteResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await rerender(<MovieScreen />);
    expect(section().getByText("Couldn't load ratings")).toBeOnTheScreen();
    await fireEvent.press(section().getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });
});

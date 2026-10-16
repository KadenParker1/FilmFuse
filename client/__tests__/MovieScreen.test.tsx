jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/movies', () => ({ ...jest.requireActual('../api/movies'), useMovie: jest.fn() }));

import { fireEvent, render, screen } from '@testing-library/react-native';
import MovieScreen from '../app/movie/[id]';
import { useMovie } from '../api/movies';
import { TMDB_ATTRIBUTION } from '../components/TmdbAttribution';
import { ApiError, NetworkError } from '../lib/apiClient';
import { formatRuntime } from '../lib/format';
import { queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';
import type { MovieDetailDTO } from '../types/api';

const mockUseMovie = useMovie as jest.Mock;

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

  it('shows Rate and Add to watchlist as disabled Phase 1b buttons', async () => {
    await render(<MovieScreen />);
    expect(screen.getByRole('button', { name: 'Rate' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Add to watchlist' })).toBeDisabled();
    expect(screen.getByText(/Phase 1b/)).toBeOnTheScreen();
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

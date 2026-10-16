jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/movies', () => ({
  ...jest.requireActual('../api/movies'),
  useGenres: jest.fn(),
  useMovieSearch: jest.fn(),
  usePopularMovies: jest.fn(),
}));

import { act, fireEvent, render, screen } from '@testing-library/react-native';
import DiscoverScreen from '../app/(tabs)/discover';
import { useGenres, useMovieSearch, usePopularMovies } from '../api/movies';
import { TMDB_ATTRIBUTION } from '../components/TmdbAttribution';
import { NetworkError } from '../lib/apiClient';
import { infiniteResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';
import type { MovieSummaryDTO } from '../types/api';

const mockGenres = useGenres as jest.Mock;
const mockSearch = useMovieSearch as jest.Mock;
const mockPopular = usePopularMovies as jest.Mock;

const movie = (id: number, title: string, releaseYear: number | null = 2020): MovieSummaryDTO => ({
  id,
  title,
  releaseYear,
  posterUrl: `https://image.tmdb.org/t/p/w500/${id}.jpg`,
  genreIds: [],
  voteAverage: 7,
});

beforeEach(() => {
  jest.useFakeTimers();
  routerState.reset();
  mockGenres.mockReturnValue(
    queryResult({
      data: [
        { id: 28, name: 'Action' },
        { id: 35, name: 'Comedy' },
      ],
    }),
  );
  mockPopular.mockImplementation((genreId: number | null) =>
    infiniteResult({ items: genreId === 35 ? [movie(3, 'Airplane!')] : [movie(1, 'Popular One'), movie(2, 'Popular Two')] }),
  );
  mockSearch.mockImplementation((q: string) =>
    q ? infiniteResult({ items: [movie(10, `Result for ${q}`, 1999)] }) : infiniteResult({ fetchStatus: 'idle' }),
  );
});

afterEach(() => {
  jest.useRealTimers();
});

const typeSearch = async (text: string) => {
  await fireEvent.changeText(screen.getByLabelText('Search movies'), text);
};
const flushDebounce = async () => {
  await act(async () => {
    jest.advanceTimersByTime(300);
  });
};

describe('DiscoverScreen', () => {
  it('shows genre chips, the popular grid and TMDB attribution when not searching', async () => {
    await render(<DiscoverScreen />);
    expect(screen.getByRole('button', { name: 'All' })).toBeSelected();
    expect(screen.getByRole('button', { name: 'Action' })).toBeOnTheScreen();
    expect(screen.getByText('Popular')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Popular One' })).toBeOnTheScreen();
    expect(screen.getByText(TMDB_ATTRIBUTION)).toBeOnTheScreen();
    expect(mockPopular).toHaveBeenLastCalledWith(null);
  });

  it('filters popular by the selected genre chip, and toggles back', async () => {
    await render(<DiscoverScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Comedy' }));
    expect(mockPopular).toHaveBeenLastCalledWith(35);
    expect(screen.getByRole('button', { name: 'Comedy' })).toBeSelected();
    expect(screen.getByRole('button', { name: 'Airplane!' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Popular One' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Comedy' }));
    expect(mockPopular).toHaveBeenLastCalledWith(null);
  });

  it('debounces search input by 300ms, then renders results', async () => {
    await render(<DiscoverScreen />);
    await typeSearch('du');
    await typeSearch('dune');
    expect(mockSearch).not.toHaveBeenCalledWith('dune');
    await act(async () => {
      jest.advanceTimersByTime(299);
    });
    expect(mockSearch).not.toHaveBeenCalledWith('dune');
    await flushDebounce();
    expect(mockSearch).toHaveBeenLastCalledWith('dune');
    expect(mockSearch).not.toHaveBeenCalledWith('du');
    expect(screen.getByText('Results for “dune”')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Result for dune' })).toBeOnTheScreen();
    expect(screen.getByText('1999')).toBeOnTheScreen();
    expect(screen.queryByText('Popular')).toBeNull();
    expect(screen.getByText(TMDB_ATTRIBUTION)).toBeOnTheScreen();
  });

  it('opens the movie detail on press', async () => {
    await render(<DiscoverScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Popular Two' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/2');
  });

  it('shows an empty state for no search results', async () => {
    mockSearch.mockImplementation((q: string) => infiniteResult(q ? { items: [] } : { fetchStatus: 'idle' }));
    await render(<DiscoverScreen />);
    await typeSearch('zzzz');
    await flushDebounce();
    expect(screen.getByText('No matches')).toBeOnTheScreen();
  });

  it('shows skeletons while the first page loads', async () => {
    mockPopular.mockReturnValue(infiniteResult());
    await render(<DiscoverScreen />);
    expect(screen.getAllByTestId('poster-skeleton', { includeHiddenElements: true }).length).toBeGreaterThan(0);
  });

  it('shows an error with retry when the first page fails', async () => {
    const refetch = jest.fn();
    mockPopular.mockReturnValue(infiniteResult({ isError: true, error: new NetworkError(), isPending: false, refetch }));
    await render(<DiscoverScreen />);
    expect(screen.getByText("Couldn't load movies")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
    expect(screen.getByText(TMDB_ATTRIBUTION)).toBeOnTheScreen();
  });

  it('fetches the next page at the end of the list only when there is one', async () => {
    const fetchNextPage = jest.fn();
    mockPopular.mockReturnValue(infiniteResult({ items: [movie(1, 'A')], hasNextPage: true, fetchNextPage }));
    await render(<DiscoverScreen />);
    await fireEvent(screen.getByTestId('poster-grid'), 'endReached');
    expect(fetchNextPage).toHaveBeenCalledTimes(1);

    fetchNextPage.mockClear();
    mockPopular.mockReturnValue(infiniteResult({ items: [movie(1, 'A')], hasNextPage: false, fetchNextPage }));
    await screen.rerender(<DiscoverScreen />);
    await fireEvent(screen.getByTestId('poster-grid'), 'endReached');
    expect(fetchNextPage).not.toHaveBeenCalled();
  });

  it('hides the genre row if genres fail, keeping popular', async () => {
    mockGenres.mockReturnValue(queryResult({ isError: true, isPending: false }));
    await render(<DiscoverScreen />);
    expect(screen.queryByRole('button', { name: 'All' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Popular One' })).toBeOnTheScreen();
  });
});

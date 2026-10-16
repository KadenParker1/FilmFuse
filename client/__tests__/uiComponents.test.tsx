import { fireEvent, render, screen } from '@testing-library/react-native';
import { Chip } from '../components/ui/Chip';
import { ErrorState, errorMessage } from '../components/ui/ErrorState';
import { PosterCard } from '../components/ui/PosterCard';
import { PosterGrid, posterColumns, type PosterItem } from '../components/ui/PosterGrid';
import { SearchBar } from '../components/ui/SearchBar';
import { Skeleton } from '../components/ui/Skeleton';
import { TMDB_ATTRIBUTION, TmdbAttribution } from '../components/TmdbAttribution';
import { ApiError, NetworkError } from '../lib/apiClient';
import { EmptyState } from '../components/ui/EmptyState';

describe('Chip', () => {
  it('exposes selected state and handles presses', async () => {
    const onPress = jest.fn();
    await render(<Chip label="Horror" selected onPress={onPress} />);
    const chip = screen.getByRole('button', { name: 'Horror' });
    expect(chip).toBeSelected();
    await fireEvent.press(chip);
    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('is unselected by default', async () => {
    await render(<Chip label="Drama" />);
    expect(screen.getByRole('button', { name: 'Drama' })).not.toBeSelected();
  });
});

describe('SearchBar', () => {
  it('reports typing and is labelled', async () => {
    const onChangeText = jest.fn();
    await render(<SearchBar label="Search movies" value="" onChangeText={onChangeText} />);
    await fireEvent.changeText(screen.getByLabelText('Search movies'), 'dune');
    expect(onChangeText).toHaveBeenCalledWith('dune');
    expect(screen.queryByRole('button', { name: 'Clear search' })).toBeNull();
  });

  it('clears via the clear button once there is text', async () => {
    const onChangeText = jest.fn();
    await render(<SearchBar value="dune" onChangeText={onChangeText} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Clear search' }));
    expect(onChangeText).toHaveBeenCalledWith('');
  });
});

describe('Skeleton', () => {
  it('renders hidden from accessibility', async () => {
    await render(<Skeleton testID="sk" className="h-4" />);
    expect(screen.getByTestId('sk', { includeHiddenElements: true })).toHaveProp('accessibilityElementsHidden', true);
    expect(screen.queryByTestId('sk')).toBeNull();
  });
});

describe('ErrorState', () => {
  it('shows the derived message and retries', async () => {
    const onRetry = jest.fn();
    await render(<ErrorState title="Search failed" error={new NetworkError()} onRetry={onRetry} />);
    expect(screen.getByText('Search failed')).toBeOnTheScreen();
    expect(screen.getByRole('alert', { name: /Couldn't reach FilmFuse/ })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('has no retry button without onRetry', async () => {
    await render(<ErrorState message="Nope" />);
    expect(screen.getByText('Nope')).toBeOnTheScreen();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('maps errors to friendly messages', () => {
    expect(errorMessage(new ApiError(400, 'Bad query'))).toBe('Bad query');
    expect(errorMessage(new ApiError(503, 'upstream'))).toMatch(/server had a problem/);
    expect(errorMessage(new Error('x'))).toBe('x');
    expect(errorMessage('weird')).toBe('Something went wrong.');
  });
});

describe('PosterCard', () => {
  it('shows the poster image and title, pressable', async () => {
    const onPress = jest.fn();
    await render(<PosterCard title="Dune" posterUrl="https://img/dune.jpg" subtitle="2021" onPress={onPress} />);
    expect(screen.getByTestId('poster-image')).toBeOnTheScreen();
    expect(screen.getByText('2021')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Dune' }));
    expect(onPress).toHaveBeenCalled();
  });

  it('falls back to the title without a poster and labels the score', async () => {
    await render(<PosterCard title="Heat" score={9} />);
    expect(screen.queryByTestId('poster-image')).toBeNull();
    expect(screen.getAllByText('Heat').length).toBeGreaterThan(0);
    expect(screen.getByLabelText('Heat, scored 9 out of 10')).toBeOnTheScreen();
  });
});

describe('posterColumns', () => {
  it.each([
    [0, 2],
    [350, 2],
    [600, 3],
    [1000, 5],
    [4000, 6],
  ])('%p px → %p columns', (width, cols) => {
    expect(posterColumns(width)).toBe(cols);
  });
});

describe('PosterGrid', () => {
  const items: PosterItem[] = [
    { id: 1, title: 'Alien', posterUrl: null, subtitle: '1979' },
    { id: 2, title: 'Arrival', posterUrl: 'https://img/arrival.jpg' },
  ];

  it('renders items and reports presses', async () => {
    const onPressItem = jest.fn();
    await render(<PosterGrid items={items} onPressItem={onPressItem} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Arrival' }));
    expect(onPressItem).toHaveBeenCalledWith(items[1]);
  });

  it('shows skeletons while loading, not items or the empty state', async () => {
    await render(<PosterGrid items={items} loading empty={<EmptyState icon="film" title="Empty" message="none" />} />);
    expect(screen.getAllByTestId('poster-skeleton', { includeHiddenElements: true }).length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Alien' })).toBeNull();
    expect(screen.queryByText('Empty')).toBeNull();
  });

  it('shows the empty state with no items', async () => {
    await render(<PosterGrid items={[]} empty={<EmptyState icon="film" title="Empty" message="none" />} />);
    expect(screen.getByText('Empty')).toBeOnTheScreen();
  });

  it('shows the loading-more spinner and footer, and pages on end reached', async () => {
    const onEndReached = jest.fn();
    await render(<PosterGrid items={items} loadingMore onEndReached={onEndReached} footer={<TmdbAttribution />} />);
    expect(screen.getByTestId('poster-grid-loading-more')).toBeOnTheScreen();
    expect(screen.getByText(TMDB_ATTRIBUTION)).toBeOnTheScreen();
    await fireEvent(screen.getByTestId('poster-grid'), 'endReached');
    expect(onEndReached).toHaveBeenCalled();
  });
});

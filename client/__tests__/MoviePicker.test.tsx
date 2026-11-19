jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { useState } from 'react';
import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { MoviePicker } from '../components/onboarding/MoviePicker';
import { serveRoutes, type Route } from '../test-utils/fetchRoutes';
import { makeMovie } from '../test-utils/fixtures';
import { renderWithClient } from '../test-utils/render';
import type { MovieSummaryDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const popular = [1, 2, 3].map((id) => makeMovie({ id, title: `Pop ${id}`, releaseYear: 2000 + id }));
const popularRoute: Route = { path: '/movies/popular', body: { items: popular, nextCursor: null } };

function Harness({ initial = [], blocked, onEdit }: { initial?: MovieSummaryDTO[]; blocked?: Map<number, string>; onEdit?: () => void }) {
  const [picks, setPicks] = useState(initial);
  return (
    <MoviePicker
      picks={picks}
      setPicks={setPicks}
      max={2}
      listName="least favourites"
      testIDPrefix="least-favorite"
      searchLabel="Search movies you couldn’t stand"
      emptyPrompt="Search for a movie you couldn’t stand."
      pickLabel={(m) => `Couldn’t stand ${m.title}`}
      blocked={blocked}
      onEdit={onEdit}
    />
  );
}

describe('MoviePicker', () => {
  it('uses its own labels, test ids and max, and reports every edit', async () => {
    serveRoutes(fetchMock, [popularRoute]);
    const onEdit = jest.fn();
    await renderWithClient(<Harness onEdit={onEdit} />);
    await waitFor(() => expect(screen.getByTestId('least-favorites-results')).toBeOnTheScreen());
    expect(screen.getByTestId('least-favorites-count')).toHaveTextContent('0 of 2 picked');

    for (const t of ['Pop 1', 'Pop 2', 'Pop 3']) {
      await fireEvent.press(screen.getByRole('checkbox', { name: `Couldn’t stand ${t}` }));
    }
    expect(screen.getByTestId('least-favorites-count')).toHaveTextContent('2 of 2 picked');
    expect(screen.getByRole('checkbox', { name: 'Couldn’t stand Pop 3' })).not.toBeChecked();
    expect(onEdit).toHaveBeenCalledTimes(3);

    await fireEvent.press(screen.getByRole('button', { name: 'Move Pop 2 up in least favourites' }));
    expect(screen.getByTestId('least-favorite-1')).toHaveTextContent(/Pop 2/);
    await fireEvent.press(screen.getByRole('button', { name: 'Remove Pop 2 from least favourites' }));
    expect(screen.getByTestId('least-favorites-count')).toHaveTextContent('1 of 2 picked');
    expect(onEdit).toHaveBeenCalledTimes(5);
  });

  it('shows blocked movies disabled with their reason, unless already picked here', async () => {
    serveRoutes(fetchMock, [popularRoute]);
    const blocked = new Map([
      [1, 'In favourites'],
      [2, 'In favourites'],
    ]);
    await renderWithClient(<Harness initial={[popular[1]!]} blocked={blocked} />);
    await waitFor(() => expect(screen.getByTestId('least-favorites-results')).toBeOnTheScreen());
    expect(screen.getByRole('checkbox', { name: 'Couldn’t stand Pop 1, in favourites' })).toBeDisabled();
    expect(screen.getByText('In favourites')).toBeOnTheScreen();
    // Already picked here: still removable.
    expect(screen.getByRole('checkbox', { name: 'Couldn’t stand Pop 2' })).toBeEnabled();
    expect(screen.getByRole('checkbox', { name: 'Couldn’t stand Pop 3' })).toBeEnabled();
  });

  it('shows the empty prompt, a failed search, and a loading state', async () => {
    serveRoutes(fetchMock, [
      { path: '/movies/popular', body: { items: [], nextCursor: null } },
      { path: '/movies/search?q=bad', status: 500, body: { message: 'boom' } },
      { path: '/movies/search?q=slow', hang: true },
    ]);
    await renderWithClient(<Harness />);
    await waitFor(() => expect(screen.getByText('Search for a movie you couldn’t stand.')).toBeOnTheScreen());

    await fireEvent.changeText(screen.getByLabelText('Search movies you couldn’t stand'), 'bad');
    await waitFor(() => expect(screen.getByText('Search failed')).toBeOnTheScreen(), { timeout: 5000 });

    await fireEvent.changeText(screen.getByLabelText('Search movies you couldn’t stand'), 'slow');
    await waitFor(() => expect(screen.getByTestId('least-favorites-loading')).toBeOnTheScreen());
  });
});

jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { CalibrationDeck } from '../components/taste/CalibrationDeck';
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

const NEXT = '/me/calibration/next?count=10';
const batchOf = (start: number, n = 10): MovieSummaryDTO[] =>
  Array.from({ length: n }, (_, i) => makeMovie({ id: start + i, title: `Movie ${start + i}`, releaseYear: 1990 + i }));
const batches = (...lists: MovieSummaryDTO[][]): Route[] => [
  ...lists.slice(0, -1).map((body) => ({ path: NEXT, body, times: 1 })),
  { path: NEXT, body: lists[lists.length - 1] },
];
const batchOk: Route = { method: 'POST', path: '/me/ratings/batch', status: 204 };

async function renderDeck(routes: Route[], props: Partial<Parameters<typeof CalibrationDeck>[0]> = {}) {
  const api = serveRoutes(fetchMock, routes);
  const onFinish = jest.fn(() => Promise.resolve());
  await renderWithClient(<CalibrationDeck deckId="test" minRated={10} skipAfter={20} onFinish={onFinish} {...props} />);
  return { api, onFinish };
}
const card = (id: number) => screen.findByTestId(`calibration-card-${id}`);
const rate = async (score: number, title: string) => {
  await fireEvent(screen.getByLabelText(`Your score for ${title}`), 'valueChange', score);
  await fireEvent.press(screen.getByRole('button', { name: `Rate ${score}` }));
};
const unseen = () => fireEvent.press(screen.getByRole('button', { name: 'Haven’t seen' }));

describe('CalibrationDeck', () => {
  it('deals one card at a time with poster, title and a slider starting at 5', async () => {
    await renderDeck(batches(batchOf(1)));
    await card(1);
    expect(screen.getByTestId('calibration-title')).toHaveTextContent('Movie 1');
    expect(screen.getByText('1990')).toBeOnTheScreen();
    expect(screen.getByLabelText('Your score for Movie 1').props.accessibilityValue.now).toBe(5);
    expect(screen.getByTestId('calibration-progress')).toHaveTextContent('Rated 0 of 10');
    expect(screen.queryByRole('button', { name: 'Done' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Skip for now' })).toBeNull();
  });

  it('records a score or "Haven’t seen", resetting the slider for the next card', async () => {
    const { api } = await renderDeck([...batches(batchOf(1, 2), []), batchOk]);
    await card(1);
    await rate(8, 'Movie 1');
    await card(2);
    expect(screen.getByLabelText('Your score for Movie 2').props.accessibilityValue.now).toBe(5);
    expect(screen.getByTestId('calibration-progress')).toHaveTextContent('Rated 1 of 10');
    await unseen();
    // The batch is over: it's submitted, then the next batch is asked for.
    await waitFor(() => expect(api.callsTo('POST', '/me/ratings/batch')).toHaveLength(1));
    expect(api.callsTo('POST', '/me/ratings/batch')[0]?.body).toEqual([
      { movieId: 1, score: 8, unseen: false },
      { movieId: 2, score: null, unseen: true },
    ]);
    await waitFor(() => expect(screen.getByTestId('calibration-out')).toBeOnTheScreen());
    expect(api.callsTo('GET', NEXT)).toHaveLength(2);
  });

  it('submits each batch of 10 before dealing the next, and offers Done after 10 rated', async () => {
    const { api, onFinish } = await renderDeck([...batches(batchOf(1), batchOf(11)), batchOk]);
    for (let i = 1; i <= 10; i++) {
      await card(i);
      await rate(7, `Movie ${i}`);
    }
    await card(11);
    expect(api.callsTo('POST', '/me/ratings/batch')).toHaveLength(1);
    expect(api.callsTo('POST', '/me/ratings/batch')[0]?.body).toHaveLength(10);
    expect(api.callsTo('GET', NEXT)).toHaveLength(2);
    expect(screen.getByTestId('calibration-progress')).toHaveTextContent('Rated 10');

    // Done submits the partial batch first.
    await rate(3, 'Movie 11');
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(onFinish).toHaveBeenCalledWith({ answered: 11 }));
    expect(api.callsTo('POST', '/me/ratings/batch')[1]?.body).toEqual([{ movieId: 11, score: 3, unseen: false }]);
  });

  it('counts ratings from an earlier sitting towards Done', async () => {
    const { api, onFinish } = await renderDeck(batches(batchOf(1)), { initialRatedCount: 10 });
    await card(1);
    expect(screen.getByTestId('calibration-progress')).toHaveTextContent('Rated 10');
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(onFinish).toHaveBeenCalledWith({ answered: 0 }));
    // Nothing pending, nothing sent.
    expect(api.callsTo('POST', '/me/ratings/batch')).toHaveLength(0);
  });

  it('offers "Skip for now" after 20 cards seen without enough rated', async () => {
    const { onFinish } = await renderDeck([...batches(batchOf(1), batchOf(11), batchOf(21)), batchOk]);
    for (let i = 1; i <= 19; i++) {
      await card(i);
      await unseen();
    }
    await card(20);
    expect(screen.queryByRole('button', { name: 'Skip for now' })).toBeNull();
    await unseen();
    await card(21);
    expect(screen.getByRole('button', { name: 'Skip for now' })).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Done' })).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Skip for now' }));
    await waitFor(() => expect(onFinish).toHaveBeenCalled());
  });

  it('never offers skip when skipAfter is null, and Done from the start when minRated is 0', async () => {
    await renderDeck(batches(batchOf(1)), { minRated: 0, skipAfter: null });
    await card(1);
    expect(screen.getByRole('button', { name: 'Done' })).toBeOnTheScreen();
    expect(screen.getByTestId('calibration-progress')).toHaveTextContent('Rated 0');
  });

  it('lets you finish when there are no more movies', async () => {
    const { onFinish } = await renderDeck(batches([]));
    await waitFor(() => expect(screen.getByTestId('calibration-out')).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(onFinish).toHaveBeenCalled());
  });

  it('retries a batch that failed to save, then deals the next one', async () => {
    const { api } = await renderDeck([
      ...batches(batchOf(1, 1), batchOf(2, 1)),
      { method: 'POST', path: '/me/ratings/batch', status: 500, body: { message: 'boom' }, times: 1 },
      batchOk,
    ]);
    await card(1);
    await rate(9, 'Movie 1');
    await waitFor(() => expect(screen.getByText(/Couldn’t save those answers/)).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await card(2);
    expect(api.callsTo('POST', '/me/ratings/batch')).toHaveLength(2);
    expect(api.callsTo('POST', '/me/ratings/batch')[1]?.body).toEqual([{ movieId: 1, score: 9, unseen: false }]);
  });

  it('stays put when finishing fails to save', async () => {
    const { onFinish } = await renderDeck(
      [...batches(batchOf(1)), { method: 'POST', path: '/me/ratings/batch', status: 500, body: { message: 'boom' } }],
      { minRated: 1 },
    );
    await card(1);
    await rate(6, 'Movie 1');
    await fireEvent.press(screen.getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.getByTestId('calibration-error')).toBeOnTheScreen());
    expect(onFinish).not.toHaveBeenCalled();
  });

  it('shows a load error with retry', async () => {
    const { api } = await renderDeck([{ path: NEXT, status: 500, body: { message: 'x' }, times: 1 }, ...batches(batchOf(1))]);
    await waitFor(() => expect(screen.getByText("Couldn't load movies")).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await card(1);
    expect(api.callsTo('GET', NEXT)).toHaveLength(2);
  });
});

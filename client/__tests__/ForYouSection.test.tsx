jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/recommendations', () => ({ useRecommendations: jest.fn(), useDismissRecommendation: jest.fn() }));
jest.mock('../api/taste', () => ({ useTasteProfile: jest.fn() }));
const mockOpenQuickRate = jest.fn();
const mockOpenRatePicker = jest.fn();
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: mockOpenQuickRate, openRatePicker: mockOpenRatePicker, showTasteUpdating: jest.fn() }),
}));

import { fireEvent, render, screen } from '@testing-library/react-native';
import { useDismissRecommendation, useRecommendations } from '../api/recommendations';
import { useTasteProfile } from '../api/taste';
import { ForYouSection } from '../components/reco/ForYouSection';
import { ApiError, NetworkError } from '../lib/apiClient';
import { makeMovie, makeRecommendation, makeTasteProfile } from '../test-utils/fixtures';
import { infiniteResult, mutationResult, queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const mockRecs = useRecommendations as jest.Mock;
const mockDismiss = useDismissRecommendation as jest.Mock;
const mockTaste = useTasteProfile as jest.Mock;

const heat = makeRecommendation({ movie: makeMovie({ id: 100, title: 'Heat' }), certification: 'R', explanation: 'Because you love crime' });
const up = makeRecommendation({ movie: makeMovie({ id: 200, title: 'Up' }), certification: null, explanation: 'Feel-good pick' });
let dismiss: ReturnType<typeof mutationResult>;

beforeEach(() => {
  routerState.reset();
  mockOpenQuickRate.mockClear();
  mockOpenRatePicker.mockClear();
  dismiss = mutationResult();
  mockDismiss.mockReturnValue(dismiss);
  mockTaste.mockReturnValue(queryResult({ data: makeTasteProfile({ status: 'FRESH' }) }));
  mockRecs.mockReturnValue(infiniteResult({ items: [heat, up] }));
});

describe('ForYouSection', () => {
  it('lists picks with poster, rating badge and explanation', async () => {
    await render(<ForYouSection />);
    expect(screen.getByRole('header', { name: 'For you' })).toBeOnTheScreen();
    expect(screen.getByTestId('rec-100')).toBeOnTheScreen();
    expect(screen.getByLabelText('Rated R')).toBeOnTheScreen();
    expect(screen.getByLabelText('Not rated')).toBeOnTheScreen();
    expect(screen.getByTestId('rec-explanation-100')).toHaveTextContent('Because you love crime');
    expect(screen.getByTestId('rec-explanation-200')).toHaveTextContent('Feel-good pick');
  });

  it('opens the movie, the quick-rate sheet ("Seen it") and dismisses', async () => {
    await render(<ForYouSection />);
    await fireEvent.press(screen.getByRole('button', { name: 'Heat' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/100');
    await fireEvent.press(screen.getByRole('button', { name: 'Seen Heat, rate it' }));
    expect(mockOpenQuickRate).toHaveBeenCalledWith(heat.movie);
    await fireEvent.press(screen.getByRole('button', { name: 'Not interested in Up' }));
    expect(dismiss.mutate).toHaveBeenCalledWith(200);
  });

  it('shows a dismiss failure (the pick is already restored by the hook)', async () => {
    mockDismiss.mockReturnValue(mutationResult({ isError: true, error: new ApiError(500, 'x') }));
    await render(<ForYouSection />);
    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn’t hide that pick/);
  });

  it('shows skeletons while loading', async () => {
    mockRecs.mockReturnValue(infiniteResult());
    await render(<ForYouSection />);
    expect(screen.getByTestId('for-you-loading')).toBeOnTheScreen();
  });

  it('asks users without a taste vector to rate a few movies', async () => {
    mockTaste.mockReturnValue(queryResult({ data: makeTasteProfile({ status: 'NONE', persona: null }) }));
    // Even a fallback list isn't shown until there's a vector.
    await render(<ForYouSection />);
    expect(screen.getByTestId('for-you-no-vector')).toBeOnTheScreen();
    expect(screen.getByText('Rate a few movies to get picks')).toBeOnTheScreen();
    expect(screen.queryByTestId('rec-100')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Rate a movie' }));
    expect(mockOpenRatePicker).toHaveBeenCalled();
  });

  it('still shows picks if the taste profile fails to load', async () => {
    mockTaste.mockReturnValue(queryResult({ isError: true, isPending: false, error: new NetworkError() }));
    await render(<ForYouSection />);
    expect(screen.getByTestId('rec-100')).toBeOnTheScreen();
  });

  it('shows an empty state when there are no picks', async () => {
    mockRecs.mockReturnValue(infiniteResult({ items: [] }));
    await render(<ForYouSection />);
    expect(screen.getByTestId('for-you-empty')).toBeOnTheScreen();
  });

  it('shows an error with retry', async () => {
    const refetch = jest.fn();
    mockRecs.mockReturnValue(infiniteResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await render(<ForYouSection />);
    expect(screen.getByText("Couldn't load your picks")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('loads more picks when there are more pages', async () => {
    const fetchNextPage = jest.fn();
    mockRecs.mockReturnValue(infiniteResult({ items: [heat], hasNextPage: true, fetchNextPage }));
    await render(<ForYouSection />);
    await fireEvent.press(screen.getByRole('button', { name: 'More picks' }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('has no "More picks" on the last page', async () => {
    await render(<ForYouSection />);
    expect(screen.queryByRole('button', { name: 'More picks' })).toBeNull();
  });
});

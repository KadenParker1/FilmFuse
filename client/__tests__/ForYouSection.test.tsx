jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../api/recommendations', () => ({ useRecommendations: jest.fn(), useDismissRecommendation: jest.fn(), useUndismissRecommendation: jest.fn() }));
jest.mock('../api/taste', () => ({ useTasteProfile: jest.fn() }));
const mockOpenQuickRate = jest.fn();
const mockOpenRatePicker = jest.fn();
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openQuickRate: mockOpenQuickRate, openRatePicker: mockOpenRatePicker, showTasteUpdating: jest.fn() }),
}));

import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { useDismissRecommendation, useRecommendations, useUndismissRecommendation } from '../api/recommendations';
import { useTasteProfile } from '../api/taste';
import { DISMISSED_MESSAGE, ForYouSection } from '../components/reco/ForYouSection';
import { UNDO_TOAST_MS, UndoToastProvider } from '../components/ui';
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
let undismiss: ReturnType<typeof mutationResult>;

beforeEach(() => {
  routerState.reset();
  mockOpenQuickRate.mockClear();
  mockOpenRatePicker.mockClear();
  dismiss = mutationResult();
  mockDismiss.mockReturnValue(dismiss);
  undismiss = mutationResult();
  (useUndismissRecommendation as jest.Mock).mockReturnValue(undismiss);
  mockTaste.mockReturnValue(queryResult({ data: makeTasteProfile({ status: 'FRESH' }) }));
  mockRecs.mockReturnValue(infiniteResult({ items: [heat, up] }));
});

describe('ForYouSection', () => {
  it('lists picks with poster, rating badge and explanation', async () => {
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    expect(screen.getByRole('header', { name: 'For you' })).toBeOnTheScreen();
    expect(screen.getByTestId('rec-100')).toBeOnTheScreen();
    expect(screen.getByLabelText('Rated R')).toBeOnTheScreen();
    expect(screen.getByLabelText('Not rated')).toBeOnTheScreen();
    expect(screen.getByTestId('rec-explanation-100')).toHaveTextContent('Because you love crime');
    expect(screen.getByTestId('rec-explanation-200')).toHaveTextContent('Feel-good pick');
  });

  it('opens the movie, the quick-rate sheet ("Seen it") and dismisses', async () => {
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    await fireEvent.press(screen.getByRole('button', { name: 'Heat' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/movie/100');
    await fireEvent.press(screen.getByRole('button', { name: 'Seen Heat, rate it' }));
    expect(mockOpenQuickRate).toHaveBeenCalledWith(heat.movie);
    await fireEvent.press(screen.getByRole('button', { name: 'Not interested in Up' }));
    expect(dismiss.mutate).toHaveBeenCalledWith(200, expect.anything());
  });

  it('shows a dismiss failure (the pick is already restored by the hook)', async () => {
    mockDismiss.mockReturnValue(mutationResult({ isError: true, error: new ApiError(500, 'x') }));
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn’t hide that pick/);
  });

  it('shows skeletons while loading', async () => {
    mockRecs.mockReturnValue(infiniteResult());
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    expect(screen.getByTestId('for-you-loading')).toBeOnTheScreen();
  });

  it('asks users without a taste vector to rate a few movies', async () => {
    mockTaste.mockReturnValue(queryResult({ data: makeTasteProfile({ status: 'NONE', persona: null }) }));
    // Even a fallback list isn't shown until there's a vector.
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    expect(screen.getByTestId('for-you-no-vector')).toBeOnTheScreen();
    expect(screen.getByText('Rate a few movies to get picks')).toBeOnTheScreen();
    expect(screen.queryByTestId('rec-100')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Rate a movie' }));
    expect(mockOpenRatePicker).toHaveBeenCalled();
  });

  it('still shows picks if the taste profile fails to load', async () => {
    mockTaste.mockReturnValue(queryResult({ isError: true, isPending: false, error: new NetworkError() }));
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    expect(screen.getByTestId('rec-100')).toBeOnTheScreen();
  });

  it('shows an empty state when there are no picks', async () => {
    mockRecs.mockReturnValue(infiniteResult({ items: [] }));
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    expect(screen.getByTestId('for-you-empty')).toBeOnTheScreen();
  });

  it('shows an error with retry', async () => {
    const refetch = jest.fn();
    mockRecs.mockReturnValue(infiniteResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    expect(screen.getByText("Couldn't load your picks")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('loads more picks when there are more pages', async () => {
    const fetchNextPage = jest.fn();
    mockRecs.mockReturnValue(infiniteResult({ items: [heat], hasNextPage: true, fetchNextPage }));
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    await fireEvent.press(screen.getByRole('button', { name: 'More picks' }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('has no "More picks" on the last page', async () => {
    await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
    expect(screen.queryByRole('button', { name: 'More picks' })).toBeNull();
  });

  describe('Not interested → Undo', () => {
    const dismissUp = async () => {
      dismiss.mutate.mockImplementation((_id: number, o: { onSuccess: () => void }) => o.onSuccess());
      await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
      await fireEvent.press(screen.getByRole('button', { name: 'Not interested in Up' }));
    };
    afterEach(() => jest.useRealTimers());

    it('shows an undo toast once the dismissal is saved; Undo un-dismisses and hides it', async () => {
      await dismissUp();
      expect(screen.getByTestId('undo-toast')).toHaveTextContent(`${DISMISSED_MESSAGE}Undo`);
      await fireEvent.press(screen.getByRole('button', { name: 'Undo hiding Up' }));
      expect(undismiss.mutateAsync).toHaveBeenCalledWith(200);
      expect(screen.queryByTestId('undo-toast')).toBeNull();
    });

    it('no toast when the dismissal fails', async () => {
      await render(<UndoToastProvider><ForYouSection /></UndoToastProvider>);
      await fireEvent.press(screen.getByRole('button', { name: 'Not interested in Up' }));
      expect(screen.queryByTestId('undo-toast')).toBeNull();
    });

    it('goes away by itself after the undo window', async () => {
      jest.useFakeTimers();
      await dismissUp();
      await act(async () => {
        jest.advanceTimersByTime(UNDO_TOAST_MS);
      });
      expect(screen.queryByTestId('undo-toast')).toBeNull();
      expect(undismiss.mutateAsync).not.toHaveBeenCalled();
    });

    it('says so when the undo fails', async () => {
      undismiss.mutateAsync.mockImplementation(() => Promise.reject(new ApiError(500, 'x')));
      await dismissUp();
      await fireEvent.press(screen.getByRole('button', { name: 'Undo hiding Up' }));
      await waitFor(() => expect(screen.getByTestId('undo-toast')).toHaveTextContent(/Couldn’t undo/));
      expect(screen.queryByRole('button', { name: /Undo/ })).toBeNull();
    });
  });
});

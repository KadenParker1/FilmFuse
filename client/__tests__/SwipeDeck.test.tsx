let mockReduceMotion = false;
jest.mock('../lib/useReduceMotion', () => ({ useReduceMotion: () => mockReduceMotion }));

import { AccessibilityInfo, Platform, StyleSheet } from 'react-native';
import { act, fireEvent, render, screen, within } from '@testing-library/react-native';
import { SwipeDeck } from '../components/sessions/SwipeDeck';
import { ApiError, NetworkError } from '../lib/apiClient';
import { makeMovie, makeResult, makeSession } from '../test-utils/fixtures';
import { mutationResult } from '../test-utils/queryResults';
import type { SessionDTO, SessionResultDTO } from '../types/api';

const res = (id: number, rank: number, extra: Partial<SessionResultDTO> = {}) =>
  makeResult({ rank, movie: makeMovie({ id, title: `Movie ${id}` }), ...extra });

const threeCards = () => makeSession({ results: [res(1, 1), res(2, 2), res(3, 3)] });

let vote: ReturnType<typeof mutationResult>;
let clear: ReturnType<typeof mutationResult>;
let refresh: ReturnType<typeof mutationResult>;
let onVeto: jest.Mock;
let onPick: jest.Mock;
let onOpenMovie: jest.Mock;
let onShowList: jest.Mock;

beforeEach(() => {
  mockReduceMotion = false;
  vote = mutationResult();
  clear = mutationResult();
  refresh = mutationResult();
  onVeto = jest.fn();
  onPick = jest.fn();
  onOpenMovie = jest.fn();
  onShowList = jest.fn();
});

const deck = (session: SessionDTO) => (
  <SwipeDeck
    session={session}
    vote={vote as never}
    clear={clear as never}
    refresh={refresh as never}
    onVeto={onVeto}
    onPick={onPick}
    onOpenMovie={onOpenMovie}
    onShowList={onShowList}
  />
);
const renderDeck = (session: SessionDTO = threeCards()) => render(deck(session));
const topCard = () => screen.getByTestId(/^swipe-card-/, { includeHiddenElements: false });
const press = (name: string) => fireEvent.press(screen.getByRole('button', { name }));

/** A PanResponder touch event: one finger moved from x=0 to `x` after `ms` milliseconds. */
function touch(x: number, ms: number) {
  return {
    nativeEvent: {},
    touchHistory: {
      numberActiveTouches: 1,
      indexOfSingleActiveTouch: 0,
      mostRecentTimeStamp: ms,
      touchBank: [
        {
          touchActive: true,
          startPageX: 0,
          startPageY: 0,
          startTimeStamp: 0,
          currentPageX: x,
          currentPageY: 0,
          currentTimeStamp: ms,
          previousPageX: 0,
          previousPageY: 0,
          previousTimeStamp: 0,
        },
      ],
    },
  };
}

/**
 * RNTL asks a touch responder's should-set handlers with no event and skips the card (which only
 * claims a touch once it moves), so the gesture calls the PanResponder handlers directly, in the
 * order the responder system does: move capture, move should-set, grant, move, release.
 */
type Handler = (e: unknown) => unknown;
const handler = (name: string) => topCard().props[name] as Handler;

async function drag(x: number, ms: number) {
  const card = topCard();
  const props = card.props as Record<string, Handler>;
  const claimed = await act(async () => {
    // Claimed halfway through; the rest of the drag arrives as a later move.
    props.onMoveShouldSetResponderCapture!(touch(x / 2, ms / 2));
    return props.onMoveShouldSetResponder!(touch(x / 2, ms / 2));
  });
  expect(claimed).toBe(true);
  await act(async () => {
    props.onResponderGrant!(touch(0, 0));
    props.onResponderMove!(touch(x, ms));
    props.onResponderRelease!(touch(x, ms));
  });
}

const a11yAction = (actionName: string) =>
  act(async () => {
    handler('onAccessibilityAction')({ nativeEvent: { actionName } });
  });

describe('SwipeDeck', () => {
  it('shows the top card with the next one peeking, hidden from screen readers', async () => {
    await renderDeck();
    expect(topCard()).toHaveProp('testID', 'swipe-card-1');
    expect(screen.getByTestId('swipe-card-2', { includeHiddenElements: true })).toBeTruthy();
    expect(screen.queryByTestId('swipe-card-2')).toBeNull();
    expect(screen.queryByTestId('swipe-card-3', { includeHiddenElements: true })).toBeNull();
    expect(within(topCard()).getByText('Card 1 of 3')).toBeOnTheScreen();
  });

  it('Yes and Nope cast UP / DOWN for the top card and move to the next', async () => {
    await renderDeck();
    await press('Yes');
    expect(vote.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 1, vote: 'UP' }, expect.anything());
    expect(topCard()).toHaveProp('testID', 'swipe-card-2');
    expect(within(topCard()).getByText('Card 2 of 3')).toBeOnTheScreen();
    await press('Nope');
    expect(vote.mutate).toHaveBeenLastCalledWith({ sessionId: 's1', movieId: 2, vote: 'DOWN' }, expect.anything());
    expect(topCard()).toHaveProp('testID', 'swipe-card-3');
  });

  it('Veto hands the top card to the screen’s confirm sheet without voting', async () => {
    await renderDeck();
    await press('Veto');
    expect(onVeto).toHaveBeenCalledWith(expect.objectContaining({ movie: expect.objectContaining({ id: 1 }) }));
    expect(vote.mutate).not.toHaveBeenCalled();
    expect(topCard()).toHaveProp('testID', 'swipe-card-1');
  });

  it('Undo clears the last vote and brings the card back, once', async () => {
    await renderDeck();
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled();
    await press('Yes');
    await press('Undo');
    expect(clear.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 1 });
    expect(topCard()).toHaveProp('testID', 'swipe-card-1');
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled();
  });

  it('skips results I already voted on, and vetoed ones (resume where I left off)', async () => {
    await renderDeck(
      makeSession({
        results: [
          res(1, 1, { myVote: 'UP', votes: { up: 1, down: 0, vetoed: false } }),
          res(2, 2, { votes: { up: 0, down: 0, vetoed: true } }),
          res(3, 3),
          res(4, 4),
        ],
      }),
    );
    expect(topCard()).toHaveProp('testID', 'swipe-card-3');
    // Card 2 of 3: one done out of the three still on the table.
    expect(within(topCard()).getByText('Card 2 of 3')).toBeOnTheScreen();
  });

  it('a failed vote puts the card back and shows the error', async () => {
    vote.mutate.mockImplementation((_v: unknown, o: { onError: () => void }) => o.onError());
    vote.isError = true;
    vote.error = new ApiError(409, 'Voting has closed');
    await renderDeck();
    await press('Yes');
    expect(topCard()).toHaveProp('testID', 'swipe-card-1');
    expect(screen.getByRole('alert')).toHaveTextContent('Voting has closed');
    expect(screen.getByRole('button', { name: 'Undo' })).toBeDisabled();
  });

  it('shows a failed undo too', async () => {
    clear.isError = true;
    clear.error = new NetworkError();
    await renderDeck();
    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn't reach/);
  });

  it('end of deck: tally, waiting count, More options and See the list', async () => {
    await renderDeck(
      makeSession({
        memberCount: 3,
        votedCount: 1,
        results: [res(1, 1, { myVote: 'UP', votes: { up: 1, down: 0, vetoed: false } })],
      }),
    );
    expect(screen.getByTestId('deck-end')).toHaveTextContent(/You’ve swiped them all/);
    expect(screen.getByTestId('deck-waiting')).toHaveTextContent('Waiting on 2 people');
    expect(screen.getByTestId('tally-panel')).toBeOnTheScreen();
    expect(screen.queryByRole('button', { name: 'Yes' })).toBeNull();
    await press('Pick the leader, Movie 1');
    expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ movie: expect.objectContaining({ id: 1 }) }));
    await press('More options');
    expect(refresh.mutate).toHaveBeenCalledWith('s1');
    await press('See the list');
    expect(onShowList).toHaveBeenCalled();
  });

  it('end of deck: no waiting line once everyone voted; refresh errors and busy state', async () => {
    refresh.isError = true;
    refresh.error = new NetworkError();
    refresh.isPending = true;
    await renderDeck(
      makeSession({ memberCount: 2, votedCount: 2, results: [res(1, 1, { myVote: 'DOWN', votes: { up: 0, down: 1, vetoed: false } })] }),
    );
    expect(screen.queryByTestId('deck-waiting')).toBeNull();
    expect(screen.getByRole('alert')).toHaveTextContent(/Couldn't reach/);
    expect(screen.getByRole('button', { name: 'More options' })).toBeBusy();
  });

  it('swiping the last card reaches the end', async () => {
    await renderDeck(makeSession({ results: [res(1, 1)] }));
    await press('Nope');
    expect(screen.getByTestId('deck-end')).toBeOnTheScreen();
    // Undo is still there to bring it back.
    await press('Undo');
    expect(topCard()).toHaveProp('testID', 'swipe-card-1');
  });

  it('no results: nothing fits yet, with More options', async () => {
    await renderDeck(makeSession({ results: [] }));
    expect(screen.getByText('Nothing fits yet')).toBeOnTheScreen();
    await press('More options');
    expect(refresh.mutate).toHaveBeenCalledWith('s1');
    expect(screen.queryByRole('button', { name: 'Undo' })).toBeNull();
  });

  it('Details opens the movie', async () => {
    await renderDeck();
    await fireEvent.press(screen.getByRole('link', { name: 'Details for Movie 1' }));
    expect(onOpenMovie).toHaveBeenCalledWith(1);
  });

  describe('match moment', () => {
    // Two members; movie 2 gets everyone's thumbs-up through a Realtime refetch.
    const before = () =>
      makeSession({ memberCount: 2, results: [res(1, 1), res(2, 2, { votes: { up: 1, down: 0, vetoed: false } }), res(3, 3)] });
    const after = () =>
      makeSession({
        memberCount: 2,
        results: [res(1, 1), res(2, 2, { votes: { up: 2, down: 0, vetoed: false } }), res(3, 3)],
      });

    it('appears when a refetch makes a movie everyone-likes; Keep swiping dismisses it for good', async () => {
      const { rerender } = await renderDeck(before());
      expect(screen.queryByTestId('match-card')).toBeNull();
      await rerender(deck(after()));
      expect(screen.getByTestId('match-card')).toHaveTextContent(/Everyone’s in! 🎉/);
      expect(screen.getByTestId('deck-live-region')).toHaveTextContent('Everyone’s in on Movie 2.');
      expect(screen.queryByRole('button', { name: 'Yes' })).toBeNull();
      await press('Keep swiping');
      expect(screen.queryByTestId('match-card')).toBeNull();
      expect(topCard()).toHaveProp('testID', 'swipe-card-1');
      await rerender(deck(after()));
      expect(screen.queryByTestId('match-card')).toBeNull();
    });

    it('Pick this opens the pick confirmation for the matched movie', async () => {
      const { rerender } = await renderDeck(before());
      await rerender(deck(after()));
      await press('Pick Movie 2');
      expect(onPick).toHaveBeenCalledWith(expect.objectContaining({ movie: expect.objectContaining({ id: 2 }) }));
    });

    it('matches already there when the deck opens do not pop up', async () => {
      await renderDeck(after());
      expect(screen.queryByTestId('match-card')).toBeNull();
    });

    it('announces the match on iOS', async () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
      const os = Platform.OS;
      Platform.OS = 'ios';
      try {
        const { rerender } = await renderDeck(before());
        await rerender(deck(after()));
        expect(announce).toHaveBeenCalledWith('Everyone’s in on Movie 2.');
      } finally {
        Platform.OS = os;
        announce.mockRestore();
      }
    });
  });

  describe('screen readers', () => {
    it('the card is one element with title, year, rating, why and position', async () => {
      await renderDeck();
      expect(topCard()).toHaveProp('accessible', true);
      expect(topCard()).toHaveProp(
        'accessibilityLabel',
        'Movie 1, 1995, R. Crime thriller you both rate highly. Card 1 of 3',
      );
    });

    it('accessibility actions vote, veto and open details', async () => {
      await renderDeck();
      const actions = (topCard().props.accessibilityActions as { label: string }[]).map((a) => a.label);
      expect(actions).toEqual(expect.arrayContaining(['Thumbs up', 'Thumbs down', 'Veto', 'Details']));
      await a11yAction('thumbsUp');
      expect(vote.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 1, vote: 'UP' }, expect.anything());
      await a11yAction('thumbsDown');
      expect(vote.mutate).toHaveBeenLastCalledWith({ sessionId: 's1', movieId: 2, vote: 'DOWN' }, expect.anything());
      await a11yAction('veto');
      expect(onVeto).toHaveBeenCalledWith(expect.objectContaining({ movie: expect.objectContaining({ id: 3 }) }));
      await a11yAction('details');
      expect(onOpenMovie).toHaveBeenCalledWith(3);
    });

    it('announces the end of the deck in the live region', async () => {
      await renderDeck(makeSession({ results: [res(1, 1)] }));
      expect(screen.getByTestId('deck-live-region')).toHaveTextContent('Card 1 of 1');
      await press('Yes');
      expect(screen.getByTestId('deck-live-region')).toHaveTextContent('You’ve swiped them all.');
    });
  });

  describe('gestures', () => {
    beforeEach(() => jest.useFakeTimers());
    afterEach(() => jest.useRealTimers());
    const settle = () => act(async () => jest.advanceTimersByTime(1000));

    it('a long drag right votes UP once the card has flown off', async () => {
      await renderDeck();
      await drag(250, 400);
      await settle();
      expect(vote.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 1, vote: 'UP' }, expect.anything());
      expect(topCard()).toHaveProp('testID', 'swipe-card-2');
    });

    it('a fast flick left votes DOWN', async () => {
      await renderDeck();
      await drag(-60, 50);
      await settle();
      expect(vote.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 1, vote: 'DOWN' }, expect.anything());
    });

    it('a short, slow drag springs back without voting', async () => {
      await renderDeck();
      await drag(40, 1000);
      await settle();
      expect(vote.mutate).not.toHaveBeenCalled();
      expect(topCard()).toHaveProp('testID', 'swipe-card-1');
    });

    it('claims only clearly horizontal drags, so the page still scrolls', async () => {
      await renderDeck();
      const claims = (x: number, ms: number) => {
        handler('onMoveShouldSetResponderCapture')(touch(x, ms));
        return handler('onMoveShouldSetResponder')(touch(x, ms));
      };
      expect(claims(4, 100)).toBe(false);
      expect(claims(40, 200)).toBe(true);
    });
  });

  describe('reduce motion', () => {
    it('renders without transforms and votes at once on release', async () => {
      mockReduceMotion = true;
      await renderDeck();
      expect(StyleSheet.flatten(topCard().props.style).transform).toBeUndefined();
      await drag(250, 400);
      expect(vote.mutate).toHaveBeenCalledWith({ sessionId: 's1', movieId: 1, vote: 'UP' }, expect.anything());
    });

    it('otherwise the card tilts and moves', async () => {
      await renderDeck();
      expect(StyleSheet.flatten(topCard().props.style).transform).toBeDefined();
    });

    it('the match card has no pop-in motion', async () => {
      mockReduceMotion = true;
      const session = makeSession({ memberCount: 2, results: [res(1, 1)] });
      const { rerender } = await renderDeck(session);
      await rerender(deck(makeSession({ memberCount: 2, results: [res(1, 1, { votes: { up: 2, down: 0, vetoed: false } })] })));
      expect(StyleSheet.flatten(screen.getByTestId('match-card').props.style ?? {}).transform).toBeUndefined();
    });
  });
});

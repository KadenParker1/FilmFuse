jest.mock('../api/movies', () => ({ ...jest.requireActual('../api/movies'), useGenres: jest.fn() }));

import { useState } from 'react';
import { StyleSheet } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { useGenres } from '../api/movies';
import { CertificationBadge } from '../components/sessions/CertificationBadge';
import { FuseMeter, fuseMeterLabel, fuseSegments } from '../components/sessions/FuseMeter';
import { LiveIndicator } from '../components/sessions/LiveIndicator';
import { SessionMembersRow } from '../components/sessions/SessionMembersRow';
import {
  EMPTY_FILTERS,
  SessionFiltersForm,
  StrategyPicker,
  toFiltersDTO,
  type SessionFilterState,
} from '../components/sessions/SessionOptions';
import { SessionResultCard } from '../components/sessions/SessionResultCard';
import { SessionSummaryRow } from '../components/sessions/SessionSummaryRow';
import {
  STRATEGIES,
  formatScore,
  orderResults,
  strategyLabel,
  validateFromYear,
} from '../components/sessions/sessionCopy';
import { NetworkError } from '../lib/apiClient';
import { makeMovie, makeResult, makeSessionMember, makeSessionSummary } from '../test-utils/fixtures';
import { queryResult } from '../test-utils/queryResults';
import type { Strategy } from '../types/api';

const mockGenres = useGenres as jest.Mock;
beforeEach(() => {
  mockGenres.mockReturnValue(
    queryResult({
      data: [
        { id: 28, name: 'Action' },
        { id: 35, name: 'Comedy' },
      ],
    }),
  );
});

const members = [
  makeSessionMember({ id: 'u1', displayName: 'Ada Lovelace', username: 'ada' }),
  makeSessionMember({ id: 'u2', displayName: 'Grace Hopper', username: 'grace' }, { voted: true }),
  makeSessionMember({ id: 'u3', displayName: 'Alan Turing', username: 'alan' }, { hasVector: false }),
];
const flexOf = (testID: string) => StyleSheet.flatten(screen.getByTestId(testID).props.style).flexGrow as number;

describe('sessionCopy', () => {
  it('has five strategies with one-line explanations', () => {
    expect(STRATEGIES.map((s) => s.label)).toEqual(['Mean', 'Least misery', 'Most pleasure', 'Closeness', 'Fairness']);
    expect(strategyLabel('CLOSENESS_WEIGHTED')).toBe('Closeness');
    for (const s of STRATEGIES) expect(s.description.length).toBeGreaterThan(10);
  });

  it.each([
    ['', null, null],
    ['  ', null, null],
    ['1990', 1990, null],
    ['199', null, /four-digit/],
    ['abcd', null, /four-digit/],
    ['1899', null, /between 1900 and 2100/],
    ['2101', null, /between 1900 and 2100/],
  ])('validateFromYear(%p)', (text, year, error) => {
    const r = validateFromYear(text);
    expect(r.year).toBe(year);
    if (error) expect(r.error).toMatch(error);
    else expect(r.error).toBeNull();
  });

  it('formats scores to one decimal', () => {
    expect(formatScore(8)).toBe('8');
    expect(formatScore(7.46)).toBe('7.5');
  });

  it('orders ranked results first and vetoed ones last', () => {
    const ordered = orderResults([
      makeResult({ rank: 2, movie: makeMovie({ id: 2 }) }),
      makeResult({ rank: 1, movie: makeMovie({ id: 1 }), votes: { up: 0, down: 0, vetoed: true } }),
      makeResult({ rank: 3, movie: makeMovie({ id: 3 }) }),
    ]);
    expect(ordered.map((r) => r.movie.id)).toEqual([2, 3, 1]);
  });

  it('toFiltersDTO sends null when nothing is set', () => {
    expect(toFiltersDTO(EMPTY_FILTERS, null)).toBeNull();
    expect(toFiltersDTO({ maxRuntime: 120, fromYear: '1990', genreIds: [28] }, 1990)).toEqual({
      maxRuntime: 120,
      minYear: 1990,
      genreIds: [28],
    });
    expect(toFiltersDTO({ ...EMPTY_FILTERS, genreIds: [] }, 2000)).toEqual({
      maxRuntime: null,
      minYear: 2000,
      genreIds: null,
    });
  });
});

describe('FuseMeter', () => {
  it('sizes segments by each member’s share of the summed fit', () => {
    const segs = fuseSegments(members, { u1: 6, u2: 3, u3: 1 });
    expect(segs.map((s) => [s.user.id, s.share])).toEqual([
      ['u1', 0.6],
      ['u2', 0.3],
      ['u3', 0.1],
    ]);
    // Colours follow member order, so a person keeps theirs across results.
    expect(new Set(segs.map((s) => s.color)).size).toBe(3);
  });

  it('skips members without a positive score', () => {
    expect(fuseSegments(members, { u1: 5, u2: 0 }).map((s) => [s.user.id, s.share])).toEqual([['u1', 1]]);
    expect(fuseSegments(members, {})).toEqual([]);
  });

  it('renders proportional segments with an accessible label listing each fit', async () => {
    await render(<FuseMeter members={members} scores={{ u1: 9, u2: 4.5, u3: 4.5 }} />);
    expect(flexOf('fuse-segment-u1')).toBeCloseTo(0.5);
    expect(flexOf('fuse-segment-u2')).toBeCloseTo(0.25);
    expect(flexOf('fuse-segment-u3')).toBeCloseTo(0.25);
    const meter = screen.getByTestId('fuse-meter');
    expect(meter.props.accessibilityLabel).toBe(
      'Fit for each person: Ada Lovelace 9 out of 10, Grace Hopper 4.5 out of 10, Alan Turing 4.5 out of 10',
    );
    expect(meter.props.accessibilityRole).toBe('image');
    // Wide segments carry the member's avatar; narrow ones don't.
    expect(screen.getAllByTestId('avatar-initials')).toHaveLength(3);
  });

  it('omits avatars from very thin segments and handles no scores', async () => {
    const { rerender } = await render(<FuseMeter members={members} scores={{ u1: 9.5, u2: 0.5 }} />);
    expect(screen.getAllByTestId('avatar-initials')).toHaveLength(1);
    await rerender(<FuseMeter members={members} scores={{}} />);
    expect(screen.getByText('No fit scores yet')).toBeOnTheScreen();
    expect(fuseMeterLabel([])).toBe('No fit scores yet');
  });
});

describe('LiveIndicator', () => {
  it.each([
    ['live', 'Live', 'Live updates on'],
    ['polling', 'Updating every few seconds', 'Updating every few seconds'],
    ['connecting', 'Connecting…', 'Connecting…'],
  ] as const)('%s', async (mode, text, label) => {
    await render(<LiveIndicator mode={mode} />);
    expect(screen.getByText(text)).toBeOnTheScreen();
    expect(screen.getByTestId('live-indicator').props.accessibilityLabel).toBe(label);
  });
});

describe('CertificationBadge', () => {
  it('labels known and unknown ratings', async () => {
    const { rerender } = await render(<CertificationBadge certification="PG_13" />);
    expect(screen.getByText('PG-13')).toBeOnTheScreen();
    expect(screen.getByLabelText('Rated PG-13')).toBeOnTheScreen();
    await rerender(<CertificationBadge certification={null} />);
    expect(screen.getByText('NR')).toBeOnTheScreen();
    expect(screen.getByLabelText('Not rated')).toBeOnTheScreen();
  });
});

describe('SessionMembersRow', () => {
  it('shows voted ticks, a count, and a hint for members with no taste profile', async () => {
    await render(<SessionMembersRow members={members} />);
    expect(screen.getByText('1 of 3 voted')).toBeOnTheScreen();
    expect(screen.getByTestId('voted-u2')).toBeOnTheScreen();
    expect(screen.queryByTestId('voted-u1')).toBeNull();
    expect(screen.getByLabelText('Grace Hopper, has voted')).toBeOnTheScreen();
    expect(screen.getByLabelText('Alan Turing, hasn’t voted yet, no taste profile yet')).toBeOnTheScreen();
    expect(screen.getByTestId('no-vector-hint')).toHaveTextContent(/^Alan Turing has no taste profile yet/);
  });

  it('joins several names and hides the hint when everyone has a profile', async () => {
    const all = members.map((m) => ({ ...m, hasVector: false }));
    const { rerender } = await render(<SessionMembersRow members={all} />);
    expect(screen.getByTestId('no-vector-hint')).toHaveTextContent(
      /^Ada Lovelace, Grace Hopper and Alan Turing have no taste profile yet/,
    );
    await rerender(<SessionMembersRow members={members.slice(0, 2)} />);
    expect(screen.queryByTestId('no-vector-hint')).toBeNull();
  });
});

describe('SessionResultCard', () => {
  const handlers = () => ({ onVote: jest.fn(), onClearVote: jest.fn(), onPick: jest.fn(), onOpenMovie: jest.fn() });

  it('shows rank, title, rating, explanation, the meter and tallies', async () => {
    const h = handlers();
    await render(
      <SessionResultCard
        result={makeResult({ votes: { up: 2, down: 1, vetoed: false } })}
        members={members}
        {...h}
      />,
    );
    expect(screen.getByText('1. Heat')).toBeOnTheScreen();
    expect(screen.getByText('R')).toBeOnTheScreen();
    expect(screen.getByText('Crime thriller you both rate highly')).toBeOnTheScreen();
    expect(screen.getByTestId('fuse-meter')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Thumbs up, 2' })).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Thumbs down, 1' })).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Heat' }));
    expect(h.onOpenMovie).toHaveBeenCalled();
  });

  it('votes, highlights my vote and taps it again to clear', async () => {
    const h = handlers();
    const { rerender } = await render(<SessionResultCard result={makeResult()} members={members} {...h} />);
    await fireEvent.press(screen.getByRole('button', { name: 'Thumbs up, 0' }));
    expect(h.onVote).toHaveBeenCalledWith('UP');
    await fireEvent.press(screen.getByRole('button', { name: 'Veto' }));
    expect(h.onVote).toHaveBeenCalledWith('VETO');
    await rerender(
      <SessionResultCard
        result={makeResult({ myVote: 'DOWN', votes: { up: 0, down: 1, vetoed: false } })}
        members={members}
        {...h}
      />,
    );
    expect(screen.getByRole('button', { name: 'Thumbs down, 1' })).toBeSelected();
    expect(screen.getByRole('button', { name: 'Thumbs up, 0' })).not.toBeSelected();
    await fireEvent.press(screen.getByRole('button', { name: 'Thumbs down, 1' }));
    expect(h.onClearVote).toHaveBeenCalled();
    await fireEvent.press(screen.getByRole('button', { name: 'Pick Heat' }));
    expect(h.onPick).toHaveBeenCalled();
  });

  it('greys out a vetoed result and hides Pick', async () => {
    await render(
      <SessionResultCard
        result={makeResult({ votes: { up: 0, down: 0, vetoed: true }, myVote: 'VETO' })}
        members={members}
        {...handlers()}
      />,
    );
    expect(screen.getByTestId('vetoed-100')).toHaveTextContent('Vetoed');
    expect(screen.queryByText('1. Heat')).toBeNull();
    expect(screen.getByRole('button', { name: 'Veto' })).toBeSelected();
    expect(screen.queryByRole('button', { name: 'Pick Heat' })).toBeNull();
  });

  it('is read-only when the session is closed', async () => {
    const h = handlers();
    await render(<SessionResultCard readOnly result={makeResult()} members={members} {...h} />);
    expect(screen.getByRole('button', { name: 'Thumbs up, 0' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Pick Heat' })).toBeNull();
  });
});

describe('SessionFiltersForm / StrategyPicker', () => {
  function Harness({ onState }: { onState: (s: SessionFilterState) => void }) {
    const [value, setValue] = useState<SessionFilterState>(EMPTY_FILTERS);
    return (
      <SessionFiltersForm
        value={value}
        onChange={(v) => {
          setValue(v);
          onState(v);
        }}
        yearError={value.fromYear === 'bad' ? 'Enter a four-digit year, like 1990.' : null}
      />
    );
  }

  it('edits runtime, from-year and genres', async () => {
    const onState = jest.fn();
    await render(<Harness onState={onState} />);
    expect(screen.getByRole('radio', { name: 'Any length' })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: 'Under 2h' }));
    expect(onState).toHaveBeenLastCalledWith({ maxRuntime: 120, fromYear: '', genreIds: [] });
    await fireEvent.changeText(screen.getByLabelText('Released from (year)'), '1990');
    await fireEvent.press(screen.getByRole('button', { name: 'Comedy' }));
    expect(onState).toHaveBeenLastCalledWith({ maxRuntime: 120, fromYear: '1990', genreIds: [35] });
    await fireEvent.press(screen.getByRole('button', { name: 'Comedy' }));
    expect(onState).toHaveBeenLastCalledWith({ maxRuntime: 120, fromYear: '1990', genreIds: [] });
    await fireEvent.changeText(screen.getByLabelText('Released from (year)'), 'bad');
    expect(screen.getByText('Enter a four-digit year, like 1990.')).toBeOnTheScreen();
  });

  it('genre loading and error', async () => {
    mockGenres.mockReturnValue(queryResult());
    const { rerender } = await render(<Harness onState={jest.fn()} />);
    expect(screen.getByTestId('genres-loading')).toBeOnTheScreen();
    mockGenres.mockReturnValue(queryResult({ isPending: false, isError: true, error: new NetworkError() }));
    await rerender(<Harness onState={jest.fn()} />);
    expect(screen.getByText('Genres unavailable right now.')).toBeOnTheScreen();
  });

  it('picks a strategy, each with its explanation', async () => {
    function S() {
      const [s, setS] = useState<Strategy>('MEAN');
      return <StrategyPicker value={s} onChange={setS} />;
    }
    await render(<S />);
    expect(screen.getAllByRole('radio')).toHaveLength(5);
    expect(screen.getByRole('radio', { name: /^Mean:/ })).toBeChecked();
    await fireEvent.press(screen.getByRole('radio', { name: /^Least misery:/ }));
    expect(screen.getByRole('radio', { name: /^Least misery:/ })).toBeChecked();
    expect(screen.getByText('Nobody gets stuck with something they’d hate.')).toBeOnTheScreen();
  });
});

describe('SessionSummaryRow', () => {
  it('shows the pick, or the status when undecided', async () => {
    const onPress = jest.fn();
    const { rerender } = await render(<SessionSummaryRow session={makeSessionSummary()} onPress={onPress} />);
    expect(screen.getByText('Heat')).toBeOnTheScreen();
    await fireEvent.press(screen.getByTestId('session-row-s1'));
    expect(onPress).toHaveBeenCalled();
    await rerender(
      <SessionSummaryRow session={makeSessionSummary({ status: 'OPEN', pickedMovie: null })} onPress={onPress} />,
    );
    expect(screen.getByText('Voting open')).toBeOnTheScreen();
    expect(screen.getByText(/^3 people/)).toBeOnTheScreen();
  });
});

import { render, screen } from '@testing-library/react-native';
import { moveItem } from '../components/onboarding/FavoritesStep';
import { clampStep, describeError, genreGate, onboardingPath, prefsToSend, TOTAL_STEPS } from '../components/onboarding/steps';
import { formatScore, parseDimensionKey, statedVsLearned } from '../components/taste/tasteCopy';
import { Toast } from '../components/ui/Toast';
import { ApiError, NetworkError } from '../lib/apiClient';

describe('clampStep / onboardingPath', () => {
  it.each([
    [0, 1],
    [1, 1],
    [5, 5],
    [8, 8],
    [9, 8],
    [-3, 1],
    [3.7, 3],
    [Number.NaN, 1],
    [null, 1],
    [undefined, 1],
  ])('%p → %p', (input, out) => {
    expect(clampStep(input)).toBe(out);
    expect(onboardingPath(input as number)).toBe(`/onboarding/${out}`);
  });

  it('has eight steps', () => expect(TOTAL_STEPS).toBe(8));
});

describe('genreGate', () => {
  const at = (...scores: number[]) => [...scores, ...Array(19 - scores.length).fill(5)];

  it('starts closed with both requirements in the hint', () => {
    const g = genreGate(at());
    expect(g).toMatchObject({ moved: 0, high: 0, ok: false });
    expect(g.hint).toBe('Move 5 more sliders off 5 and put 3 more genres at 8 or higher.');
  });

  it('counts sliders at 8+ as moved too', () => {
    const g = genreGate(at(8, 9, 10));
    expect(g).toMatchObject({ moved: 3, high: 3, ok: false });
    expect(g.hint).toBe('Move 2 more sliders off 5.');
  });

  it('uses singular wording for one more', () => {
    expect(genreGate(at(8, 9, 10, 1)).hint).toBe('Move 1 more slider off 5.');
    expect(genreGate(at(8, 9, 1, 2, 3)).hint).toBe('Put 1 more genre at 8 or higher.');
  });

  it('needs three at 8 or higher even with many moved', () => {
    const g = genreGate(at(1, 2, 3, 4, 6, 7, 8, 9));
    expect(g).toMatchObject({ moved: 8, high: 2, ok: false });
  });

  it('opens at 5 moved with 3 at 8+', () => {
    const g = genreGate(at(8, 8, 8, 1, 2));
    expect(g).toMatchObject({ moved: 5, high: 3, ok: true });
    expect(g.hint).toMatch(/plenty/);
  });
});

describe('prefsToSend', () => {
  it('sends saved and touched keys, in display order, defaulting to 5', () => {
    const values = new Map([
      [1, 9],
      [3, 2],
    ]);
    expect(prefsToSend([1, 2, 3, 4], values, new Set([3, 4]), new Set([1]))).toEqual([
      [1, 9],
      [3, 2],
      [4, 5],
    ]);
  });
});

describe('describeError', () => {
  it('joins field errors, else uses the friendly message', () => {
    expect(describeError(null)).toBeNull();
    expect(describeError(new ApiError(400, 'Invalid', { a: 'A is bad.', b: 'B too.' }))).toBe('A is bad. B too.');
    expect(describeError(new ApiError(400, 'Pick at least 3 favourites'))).toBe('Pick at least 3 favourites');
    expect(describeError(new ApiError(503, 'x'))).toMatch(/server had a problem/);
    expect(describeError(new NetworkError())).toMatch(/Couldn't reach FilmFuse/);
  });
});

describe('moveItem', () => {
  it('swaps neighbours and ignores moves off the ends', () => {
    expect(moveItem(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(moveItem(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b']);
    const list = ['a', 'b'];
    expect(moveItem(list, 0, -1)).toBe(list);
    expect(moveItem(list, 1, 1)).toBe(list);
  });
});

describe('taste copy', () => {
  it('formats learned scores with one decimal and stated ones whole', () => {
    expect(formatScore(7)).toBe('7');
    expect(formatScore(7.44)).toBe('7.4');
  });

  it.each([
    [4, 7, 'You said 4 · your ratings say 7'],
    [8, 7.4, 'You said 8 · your ratings say 7.4'],
    [null, 3.2, 'Not set · your ratings say 3.2'],
    [6, null, 'You said 6 · not enough ratings yet'],
  ])('stated %p / learned %p', (stated, learned, out) => expect(statedVsLearned(stated, learned)).toBe(out));

  it('parses dimension keys', () => {
    expect(parseDimensionKey('genre:28')).toEqual({ kind: 'genre', id: '28' });
    expect(parseDimensionKey('mood:edge-of-seat')).toEqual({ kind: 'mood', id: 'edge-of-seat' });
    expect(parseDimensionKey('genre:')).toBeNull();
    expect(parseDimensionKey('other:1')).toBeNull();
    expect(parseDimensionKey('nope')).toBeNull();
  });
});

describe('Toast', () => {
  it('renders nothing without a message', async () => {
    await render(<Toast message={null} />);
    expect(screen.queryByTestId('toast')).toBeNull();
  });

  it('shows a spinner while busy and an icon otherwise', async () => {
    await render(<Toast message="Taste updating…" tone="busy" />);
    expect(screen.getByTestId('toast')).toHaveTextContent('Taste updating…');
    expect(screen.getByTestId('toast-spinner')).toBeOnTheScreen();
    await render(<Toast message="Taste updated" tone="success" />);
    expect(screen.getByTestId('icon-checkmark-circle')).toBeOnTheScreen();
    await render(<Toast message="Oops" tone="error" />);
    expect(screen.getByTestId('icon-alert-circle')).toBeOnTheScreen();
  });
});

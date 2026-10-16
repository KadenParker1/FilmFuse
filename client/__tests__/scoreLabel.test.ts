import { clampScore, scoreLabel } from '../components/ui/scoreLabel';

describe('scoreLabel', () => {
  it.each([
    [1, "can't stand"],
    [2, "can't stand"],
    [3, 'not for me'],
    [4, 'not for me'],
    [5, 'fine'],
    [6, 'fine'],
    [7, 'really like'],
    [8, 'really like'],
    [9, 'all-time'],
    [10, 'all-time'],
  ])('maps %i to "%s"', (n, label) => {
    expect(scoreLabel(n)).toBe(label);
  });

  it('clamps and rounds out-of-range input', () => {
    expect(clampScore(0)).toBe(1);
    expect(clampScore(11)).toBe(10);
    expect(clampScore(6.6)).toBe(7);
    expect(clampScore(Number.NaN)).toBe(1);
    expect(scoreLabel(42)).toBe('all-time');
  });
});

import {
  actionLabel,
  ADMIN_ACTIONS,
  auditValue,
  formatCount,
  formatMb,
  formatUtc,
  percent,
  runDuration,
  shortDay,
  summaryHighlights,
  triggeredByLabel,
  validateIntegerSetting,
  validateReportNote,
} from '../lib/admin';
import { UNSAFE_TEXT_MESSAGE } from '../lib/safeText';

const votes = { min: 0, max: 100, unit: 'votes' };

describe('validateIntegerSetting', () => {
  it('accepts whole numbers within the inclusive bounds', () => {
    expect(validateIntegerSetting(votes, '0')).toEqual({ ok: true, value: 0 });
    expect(validateIntegerSetting(votes, ' 100 ')).toEqual({ ok: true, value: 100 });
  });

  it.each([
    ['', 'Enter a number.'],
    ['   ', 'Enter a number.'],
    ['5.5', 'Must be a whole number.'],
    ['1e3', 'Must be a whole number.'],
    ['abc', 'Must be a whole number.'],
    ['-1', 'Must be between 0 and 100 votes.'],
    ['101', 'Must be between 0 and 100 votes.'],
    ['99999999999999999999', 'Must be between 0 and 100 votes.'],
  ])('refuses %j with the server wording', (input, error) => {
    expect(validateIntegerSetting(votes, input)).toEqual({ ok: false, error });
  });

  it('leaves the unit out when there is none', () => {
    expect(validateIntegerSetting({ min: 1, max: 2, unit: null }, '3')).toEqual({
      ok: false,
      error: 'Must be between 1 and 2.',
    });
  });
});

describe('validateReportNote', () => {
  it('allows empty, ordinary and multi-line notes', () => {
    expect(validateReportNote('')).toBeNull();
    expect(validateReportNote('Suspended.\nSee thread.')).toBeNull();
  });

  it('caps the length and refuses invisible formatting', () => {
    expect(validateReportNote('x'.repeat(501))).toBe('Notes cannot exceed 500 characters');
    expect(validateReportNote('bad‮text')).toBe(UNSAFE_TEXT_MESSAGE);
  });
});

describe('formatting', () => {
  it('formats counts without a locale', () => {
    expect(formatCount(0)).toBe('0');
    expect(formatCount(1234567)).toBe('1,234,567');
    expect(formatCount(-1200)).toBe('-1,200');
    expect(formatCount(null)).toBe('—');
    expect(formatCount(Number.NaN)).toBe('—');
  });

  it('formats sizes and shares', () => {
    expect(formatMb(312.4 * 1024 * 1024)).toBe('312.4 MB');
    expect(percent(1, 3)).toBe('33%');
    expect(percent(5, 0)).toBe('0%');
  });

  it('formats UTC days and times', () => {
    expect(shortDay('2026-10-03')).toBe('Oct 3');
    expect(shortDay('nope')).toBe('nope');
    expect(formatUtc('2026-10-24T04:05:00Z')).toBe('Oct 24, 04:05 UTC');
    expect(formatUtc(null)).toBe('—');
    expect(formatUtc('garbage')).toBe('—');
  });

  it('describes run durations', () => {
    expect(runDuration('2026-10-23T04:00:00Z', null)).toBeNull();
    expect(runDuration('2026-10-23T04:00:00Z', '2026-10-23T04:00:42Z')).toBe('42s');
    expect(runDuration('2026-10-23T04:00:00Z', '2026-10-23T04:03:05Z')).toBe('3m 05s');
    expect(runDuration('2026-10-23T04:00:00Z', '2026-10-23T05:12:00Z')).toBe('1h 12m');
  });

  it('names who started a run', () => {
    expect(triggeredByLabel(null)).toBe('Scheduled');
    expect(triggeredByLabel('cli')).toBe('Seed CLI');
    expect(triggeredByLabel('admin:abc')).toBe('Run now (admin)');
    expect(triggeredByLabel('other')).toBe('other');
  });

  it('picks the numeric summary entries', () => {
    expect(summaryHighlights({ fetched: 1200, error: 'x', failed: 2, a: 1, b: 2, c: 3 })).toEqual([
      'fetched 1,200',
      'failed 2',
      'a 1',
      'b 2',
    ]);
  });

  it('labels every audit action and shows values', () => {
    expect(ADMIN_ACTIONS).toHaveLength(7);
    ADMIN_ACTIONS.forEach((a) => expect(actionLabel(a)).not.toBe(a));
    expect(auditValue(null)).toBe('—');
    expect(auditValue(undefined)).toBe('—');
    expect(auditValue(12)).toBe('12');
    expect(auditValue(false)).toBe('false');
    expect(auditValue('x')).toBe('x');
    expect(auditValue({ status: 'OPEN' })).toBe('{"status":"OPEN"}');
  });
});

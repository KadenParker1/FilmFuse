import type { SessionResultDTO, SessionStatus, Strategy } from '../../types/api';

export const STRATEGIES: { value: Strategy; label: string; description: string }[] = [
  { value: 'MEAN', label: 'Mean', description: 'The best fit on average across everyone.' },
  { value: 'LEAST_MISERY', label: 'Least misery', description: 'Nobody gets stuck with something they’d hate.' },
  { value: 'MOST_PLEASURE', label: 'Most pleasure', description: 'Someone here is going to love it.' },
  { value: 'CLOSENESS_WEIGHTED', label: 'Closeness', description: 'Closer friends’ tastes count a little more.' },
  { value: 'FAIRNESS', label: 'Fairness', description: 'Balances the fit so no one is left out.' },
];

export const DEFAULT_STRATEGY: Strategy = 'MEAN';

export function strategyLabel(strategy: Strategy): string {
  return STRATEGIES.find((s) => s.value === strategy)?.label ?? strategy;
}

/** Max-runtime chips (minutes); null = any length. */
export const RUNTIME_CHOICES: { value: number | null; label: string }[] = [
  { value: null, label: 'Any length' },
  { value: 90, label: 'Under 1h 30' },
  { value: 120, label: 'Under 2h' },
  { value: 150, label: 'Under 2h 30' },
];

export const MIN_YEAR = 1900;
export const MAX_YEAR = 2100;

/** Validates the optional "from year" field: blank is fine; otherwise a whole year 1900–2100. */
export function validateFromYear(text: string): { year: number | null; error: string | null } {
  const t = text.trim();
  if (!t) return { year: null, error: null };
  if (!/^\d{4}$/.test(t)) return { year: null, error: 'Enter a four-digit year, like 1990.' };
  const year = Number(t);
  if (year < MIN_YEAR || year > MAX_YEAR) return { year: null, error: `Pick a year between ${MIN_YEAR} and ${MAX_YEAR}.` };
  return { year, error: null };
}

export const STATUS_LABELS: Record<SessionStatus, string> = {
  OPEN: 'Voting open',
  DECIDED: 'Decided',
  EXPIRED: 'Expired',
};

/** A member score (1–10, may be fractional) for display: "7.5", "8". */
export function formatScore(score: number): string {
  return String(Math.round(score * 10) / 10);
}

/** Ranked results first (by rank), vetoed ones greyed at the bottom — also right after an optimistic veto. */
export function orderResults(results: SessionResultDTO[]): SessionResultDTO[] {
  return [...results].sort((a, b) => Number(a.votes.vetoed) - Number(b.votes.vetoed) || a.rank - b.rank);
}

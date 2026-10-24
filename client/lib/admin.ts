import type { AdminAction, AdminSettingDTO } from '../types/api';
import { isSafeText, UNSAFE_TEXT_MESSAGE } from './safeText';

/** Resolution notes are at most this long (server: ResolveReportDTO). */
export const REPORT_NOTE_MAX = 500;

export type SettingInputResult = { ok: true; value: number } | { ok: false; error: string };

/**
 * Checks what an admin typed for an INTEGER setting before it is sent, with the server's wording:
 * a whole number within the setting's inclusive bounds. The server checks again (and is the authority).
 */
export function validateIntegerSetting(setting: Pick<AdminSettingDTO, 'min' | 'max' | 'unit'>, input: string): SettingInputResult {
  const text = input.trim();
  if (text === '') return { ok: false, error: 'Enter a number.' };
  if (!/^-?\d+$/.test(text)) return { ok: false, error: 'Must be a whole number.' };
  const value = Number(text);
  const { min, max, unit } = setting;
  if (!Number.isSafeInteger(value) || (min != null && value < min) || (max != null && value > max)) {
    return { ok: false, error: `Must be between ${min} and ${max}${unit ? ` ${unit}` : ''}.` };
  }
  return { ok: true, value };
}

/** A resolution note: optional, at most 500 characters, no control / invisible formatting characters. */
export function validateReportNote(note: string): string | null {
  if (note.trim().length > REPORT_NOTE_MAX) return `Notes cannot exceed ${REPORT_NOTE_MAX} characters`;
  if (!isSafeText(note, true)) return UNSAFE_TEXT_MESSAGE;
  return null;
}

/** 12345 → "12,345" (no locale dependency, so tests and every platform agree). */
export function formatCount(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  const sign = value < 0 ? '-' : '';
  const digits = Math.round(Math.abs(value)).toString();
  return sign + digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** Bytes as MB with one decimal ("312.4 MB"); MB = 1024 × 1024 bytes, as the size guard counts. */
export function formatMb(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** part / whole as a whole percentage ("42%"); "0%" for an empty whole. */
export function percent(part: number, whole: number): string {
  if (!whole) return '0%';
  return `${Math.round((part / whole) * 100)}%`;
}

const ACTION_LABELS: Record<AdminAction, string> = {
  SETTING_UPDATE: 'Setting changed',
  SETTING_RESET: 'Setting reset',
  JOB_RUN: 'Job run',
  JOB_PAUSE: 'Job paused',
  JOB_RESUME: 'Job resumed',
  REPORT_RESOLVE: 'Report resolved',
  REPORT_DISMISS: 'Report dismissed',
};

export const ADMIN_ACTIONS = Object.keys(ACTION_LABELS) as AdminAction[];

export function actionLabel(action: AdminAction): string {
  return ACTION_LABELS[action] ?? action;
}

/** An audit value for display: "—" for none, JSON otherwise (strings unquoted). */
export function auditValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return JSON.stringify(value);
}

/** "2026-10-23" → "Oct 23" in UTC (the server's days are UTC). */
export function shortDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number);
  if (!y || !m || !d) return isoDate;
  const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  return `${months[m - 1]} ${d}`;
}

/** "2026-10-24T04:00:00Z" → "Oct 24, 04:00 UTC" (job schedules are UTC). */
export function formatUtc(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  const day = shortDay(`${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`);
  return `${day}, ${pad(date.getUTCHours())}:${pad(date.getUTCMinutes())} UTC`;
}

/** How long a run took ("42s", "3m 05s", "1h 12m"); null while it runs. */
export function runDuration(startedAt: string, finishedAt: string | null): string | null {
  if (!finishedAt) return null;
  const seconds = Math.round((new Date(finishedAt).getTime() - new Date(startedAt).getTime()) / 1000);
  if (!Number.isFinite(seconds)) return null;
  const s = Math.max(0, seconds);
  if (s < 60) return `${s}s`;
  const minutes = Math.floor(s / 60);
  if (minutes < 60) return `${minutes}m ${String(s % 60).padStart(2, '0')}s`;
  return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
}

/** Who started a run: the scheduler, the seed CLI or an admin. */
export function triggeredByLabel(triggeredBy: string | null): string {
  if (!triggeredBy) return 'Scheduled';
  if (triggeredBy === 'cli') return 'Seed CLI';
  if (triggeredBy.startsWith('admin:')) return 'Run now (admin)';
  return triggeredBy;
}

/** The numbers of a run summary worth a glance (numeric entries only, at most `max`). */
export function summaryHighlights(summary: Record<string, unknown>, max = 4): string[] {
  return Object.entries(summary)
    .filter((entry): entry is [string, number] => typeof entry[1] === 'number')
    .slice(0, max)
    .map(([key, value]) => `${key} ${formatCount(value)}`);
}

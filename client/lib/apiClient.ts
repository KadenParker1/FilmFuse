import { supabase } from './supabase';
import { env } from './env';
import type { ErrorResponseDTO } from '../types/api';

/** A non-2xx answer from core-api, carrying its `{message, fieldErrors?}` body. */
export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: Record<string, string>;

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
  }
}

/** The request never got an HTTP answer (offline, DNS, CORS, server down). */
export class NetworkError extends Error {
  constructor(cause?: unknown) {
    super("Couldn't reach FilmFuse. Check your connection and try again.");
    this.name = 'NetworkError';
    if (cause !== undefined) (this as { cause?: unknown }).cause = cause;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/** Turns a failed response into an ApiError, tolerating non-JSON bodies (proxy error pages). */
export async function toApiError(res: Response): Promise<ApiError> {
  let body: unknown = null;
  try {
    body = await res.json();
  } catch {
    // not JSON — fall back to a generic message
  }
  const parsed: Partial<ErrorResponseDTO> = isRecord(body) ? body : {};
  const message =
    typeof parsed.message === 'string' && parsed.message.length > 0
      ? parsed.message
      : `Request failed with status ${res.status}`;
  const fieldErrors = isRecord(parsed.fieldErrors)
    ? Object.fromEntries(
        Object.entries(parsed.fieldErrors).filter((e): e is [string, string] => typeof e[1] === 'string'),
      )
    : {};
  return new ApiError(res.status, message, fieldErrors);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const {
    data: { session },
  } = await supabase.auth.getSession();

  const headers: Record<string, string> = {
    Accept: 'application/json',
    ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    ...(init.headers as Record<string, string> | undefined),
  };
  if (session?.access_token) {
    headers.Authorization = `Bearer ${session.access_token}`;
  }

  let res: Response;
  try {
    res = await fetch(`${env.apiBaseUrl}${path}`, { ...init, headers });
  } catch (e) {
    throw new NetworkError(e);
  }

  if (!res.ok) throw await toApiError(res);
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const withBody = (method: string, body: unknown): RequestInit => ({
  method,
  body: body !== undefined ? JSON.stringify(body) : undefined,
});

export const apiClient = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, withBody('POST', body)),
  patch: <T>(path: string, body?: unknown) => request<T>(path, withBody('PATCH', body)),
  put: <T>(path: string, body?: unknown) => request<T>(path, withBody('PUT', body)),
  /** `keepalive` (web) lets the request finish while the page unloads; native ignores it. */
  del: <T>(path: string, options: { keepalive?: boolean } = {}) =>
    request<T>(path, options.keepalive ? { method: 'DELETE', keepalive: true } : { method: 'DELETE' }),
};

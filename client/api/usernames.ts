import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import type { UsernameAvailabilityDTO } from '../types/api';

/** Mirrors the server rule: 3–30 chars of [a-z0-9_] after trimming and lowercasing. */
export const USERNAME_RE = /^[a-z0-9_]{3,30}$/;

export const normalizeUsername = (raw: string) => raw.trim().toLowerCase();

export const fetchUsernameAvailability = (username: string) =>
  apiClient.get<UsernameAvailabilityDTO>(`/usernames/${encodeURIComponent(username)}/available`);

export type UsernameStatus = 'idle' | 'unchanged' | 'invalid' | 'checking' | 'available' | 'taken' | 'error';

/**
 * Pure mapping from inputs + query state to what the edit screen shows. `current` is the user's
 * own username, which is never "taken" for them.
 */
export function usernameStatus(args: {
  username: string;
  current: string | null | undefined;
  isFetching: boolean;
  isError: boolean;
  data: UsernameAvailabilityDTO | undefined;
}): UsernameStatus {
  const u = normalizeUsername(args.username);
  if (u.length === 0) return 'idle';
  if (args.current && u === args.current) return 'unchanged';
  if (!USERNAME_RE.test(u)) return 'invalid';
  if (args.isError) return 'error';
  if (!args.data || args.data.username !== u || args.isFetching) return 'checking';
  if (!args.data.valid) return 'invalid';
  return args.data.available ? 'available' : 'taken';
}

/**
 * Availability of an already-debounced username. Only asks the server about well-formed names that
 * differ from the caller's current one.
 */
export function useUsernameAvailability(debouncedUsername: string, current?: string | null) {
  const u = normalizeUsername(debouncedUsername);
  const enabled = USERNAME_RE.test(u) && u !== current;
  const query = useQuery({
    queryKey: queryKeys.usernameAvailability(u),
    queryFn: () => fetchUsernameAvailability(u),
    enabled,
    staleTime: 10_000,
  });
  return query;
}

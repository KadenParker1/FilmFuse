jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: null } })) } },
}));

import { waitFor } from '@testing-library/react-native';
import { usernameStatus, useUsernameAvailability } from '../api/usernames';
import { jsonResponse, renderHookWithClient } from '../test-utils/render';

const base = { current: 'ada', isFetching: false, isError: false, data: undefined };

describe('usernameStatus', () => {
  it.each([
    ['', base, 'idle'],
    ['  ADA ', base, 'unchanged'],
    ['ab', base, 'invalid'],
    ['has space', base, 'invalid'],
    ['dash-es', base, 'invalid'],
    ['new_name', base, 'checking'],
    ['new_name', { ...base, isFetching: true, data: { username: 'new_name', valid: true, available: true } }, 'checking'],
    ['new_name', { ...base, data: { username: 'other', valid: true, available: true } }, 'checking'],
    ['new_name', { ...base, data: { username: 'new_name', valid: true, available: true } }, 'available'],
    ['New_Name', { ...base, data: { username: 'new_name', valid: true, available: false } }, 'taken'],
    ['new_name', { ...base, data: { username: 'new_name', valid: false, available: false } }, 'invalid'],
    ['new_name', { ...base, isError: true }, 'error'],
    ['new_name', { ...base, current: null, data: { username: 'new_name', valid: true, available: true } }, 'available'],
  ] as const)('%p %#', (username, args, expected) => {
    expect(usernameStatus({ ...args, username })).toBe(expected);
  });
});

describe('useUsernameAvailability', () => {
  const fetchMock = jest.fn();
  beforeEach(() => {
    fetchMock.mockReset();
    global.fetch = fetchMock as unknown as typeof fetch;
  });

  it('GETs the normalised username', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { username: 'popcorn', valid: true, available: true }));
    const { result } = await renderHookWithClient(() => useUsernameAvailability(' Popcorn ', 'ada'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock.mock.calls[0][0]).toBe('http://localhost:8080/api/v1/usernames/popcorn/available');
  });

  it.each([['ab'], ['bad name'], ['ada']])('does not ask about %p', async (u) => {
    const { result } = await renderHookWithClient(() => useUsernameAvailability(u, 'ada'));
    expect(result.current.fetchStatus).toBe('idle');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

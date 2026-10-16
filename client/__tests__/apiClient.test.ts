jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn() } },
}));

import { ApiError, NetworkError, apiClient } from '../lib/apiClient';
import { supabase } from '../lib/supabase';

const getSession = supabase.auth.getSession as jest.Mock;

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
  } as unknown as Response;
}

describe('apiClient', () => {
  const fetchMock = jest.fn();

  beforeEach(() => {
    fetchMock.mockReset();
    global.fetch = fetchMock as unknown as typeof fetch;
    getSession.mockResolvedValue({ data: { session: { access_token: 'tok-123' } } });
  });

  it('attaches the Supabase access token and parses JSON', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, { id: 'u1' }));
    await expect(apiClient.get('/me')).resolves.toEqual({ id: 'u1' });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('http://localhost:8080/api/v1/me');
    expect(init.headers.Authorization).toBe('Bearer tok-123');
  });

  it('sends no Authorization header when signed out', async () => {
    getSession.mockResolvedValue({ data: { session: null } });
    fetchMock.mockResolvedValue(jsonResponse(200, {}));
    await apiClient.get('/me');
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });

  it('turns a {message, fieldErrors} body into an ApiError', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, { message: 'Validation failed', fieldErrors: { username: 'Username must be 3-30 characters' } }),
    );
    const err = await apiClient.patch('/me', { username: 'x' }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({
      status: 400,
      message: 'Validation failed',
      fieldErrors: { username: 'Username must be 3-30 characters' },
    });
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: 'PATCH', body: JSON.stringify({ username: 'x' }) });
  });

  it('falls back to a generic message for non-JSON error bodies', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 502,
      json: () => Promise.reject(new SyntaxError('Unexpected token <')),
    } as unknown as Response);
    const err = await apiClient.get('/me').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err).toMatchObject({ status: 502, message: 'Request failed with status 502', fieldErrors: {} });
  });

  it('wraps fetch failures in a NetworkError', async () => {
    fetchMock.mockRejectedValue(new TypeError('Network request failed'));
    await expect(apiClient.get('/me')).rejects.toBeInstanceOf(NetworkError);
  });

  it('returns undefined for 204 No Content', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 204, json: () => Promise.reject(new Error('no body')) });
    await expect(apiClient.del('/me')).resolves.toBeUndefined();
  });
});

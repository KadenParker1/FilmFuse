jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(), exchangeCodeForSession: jest.fn() } },
}));

import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import AuthCallbackScreen from '../app/auth/callback';
import { completeAuthCallback } from '../auth/completeAuthCallback';
import { supabase } from '../lib/supabase';
import { routerState } from '../test-utils/router';

const getSession = supabase.auth.getSession as jest.Mock;
const exchange = supabase.auth.exchangeCodeForSession as jest.Mock;

beforeEach(() => {
  routerState.reset();
  getSession.mockReset().mockResolvedValue({ data: { session: null } });
  exchange.mockReset().mockResolvedValue({ data: {}, error: null });
});

describe('completeAuthCallback', () => {
  it('exchanges the code when there is no session yet', async () => {
    await expect(completeAuthCallback({ code: 'abc' })).resolves.toEqual({ ok: true });
    expect(exchange).toHaveBeenCalledWith('abc');
  });

  it('skips the exchange when supabase-js already did it (web)', async () => {
    getSession.mockResolvedValue({ data: { session: { access_token: 't' } } });
    await expect(completeAuthCallback({ code: 'abc' })).resolves.toEqual({ ok: true });
    expect(exchange).not.toHaveBeenCalled();
  });

  it('reports provider errors, missing codes and failed exchanges', async () => {
    await expect(completeAuthCallback({ error: 'access_denied' })).resolves.toEqual({ ok: false, error: 'access_denied' });
    await expect(completeAuthCallback({})).resolves.toMatchObject({ ok: false });
    exchange.mockResolvedValue({ data: {}, error: { message: 'code verifier missing' } });
    await expect(completeAuthCallback({ code: 'abc' })).resolves.toEqual({ ok: false, error: 'code verifier missing' });
    exchange.mockRejectedValue(new Error('network'));
    await expect(completeAuthCallback({ code: 'abc' })).resolves.toEqual({ ok: false, error: 'network' });
  });
});

describe('AuthCallbackScreen', () => {
  it('exchanges the code then goes home', async () => {
    routerState.params = { code: 'the-code' };
    await render(<AuthCallbackScreen />);
    expect(screen.getByLabelText('Signing you in')).toBeOnTheScreen();
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/'));
    expect(exchange).toHaveBeenCalledWith('the-code');
    expect(exchange).toHaveBeenCalledTimes(1);
  });

  it('shows the error inline with a way back to sign in', async () => {
    routerState.params = { error: 'access_denied', error_description: 'The user denied access' };
    await render(<AuthCallbackScreen />);
    expect(await screen.findByText('The user denied access')).toBeOnTheScreen();
    expect(screen.getByText("Couldn't sign you in")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Back to sign in' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/sign-in');
    expect(exchange).not.toHaveBeenCalled();
  });

  it('shows a failed exchange', async () => {
    routerState.params = { code: 'stale' };
    exchange.mockResolvedValue({ data: {}, error: { message: 'invalid flow state, no valid flow state found' } });
    await render(<AuthCallbackScreen />);
    expect(await screen.findByText(/invalid flow state/)).toBeOnTheScreen();
    expect(routerState.router.replace).not.toHaveBeenCalled();
  });
});

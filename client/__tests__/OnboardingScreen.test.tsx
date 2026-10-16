jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));
const mockSignOut = jest.fn(() => Promise.resolve());
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ signOut: mockSignOut }) }));
jest.mock('../api/usernames', () => ({ ...jest.requireActual('../api/usernames'), useUsernameAvailability: jest.fn() }));
jest.mock('../components/profile/profilePhoto', () => ({
  canUseCamera: false,
  pickSquarePhoto: jest.fn(),
  prepareProfilePhoto: jest.fn(),
}));

import { fireEvent, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import OnboardingScreen from '../app/(onboarding)/onboarding';
import { useUsernameAvailability } from '../api/usernames';
import { pickSquarePhoto } from '../components/profile/profilePhoto';
import { queryKeys } from '../lib/queryKeys';
import { makeProfile } from '../test-utils/fixtures';
import { queryResult } from '../test-utils/queryResults';
import { createTestQueryClient, jsonResponse, renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';
import type { ProfileResponseDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const BASE = 'http://localhost:8080/api/v1';
const mockAvailability = useUsernameAvailability as jest.Mock;
const fetchMock = jest.fn();

const fresh = makeProfile({
  username: null,
  displayName: 'Ada Lovelace',
  bio: null,
  onboardingStep: 0,
  onboardingCompleted: false,
  onboardingCompletedAt: null,
});
const completed = (overrides: Partial<ProfileResponseDTO> = {}) =>
  makeProfile({
    ...fresh,
    onboardingStep: 1,
    onboardingCompleted: true,
    onboardingCompletedAt: '2026-10-16T00:00:00Z',
    ...overrides,
  });

type Route = { method: string; path: string; status: number; body: unknown };
/** Answers fetches by method + path; GET /me answers `me` unless a route overrides it. */
function serve(me: ProfileResponseDTO | null, routes: Route[] = []) {
  fetchMock.mockImplementation((url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET';
    const path = url.replace(BASE, '');
    const route = routes.find((r) => r.method === method && r.path === path);
    if (route) return Promise.resolve(jsonResponse(route.status, route.body));
    if (method === 'GET' && path === '/me' && me) return Promise.resolve(jsonResponse(200, me));
    return Promise.resolve(jsonResponse(500, { message: `unexpected ${method} ${path}` }));
  });
}
const calls = () =>
  fetchMock.mock.calls.map(([url, init]: [string, RequestInit | undefined]) => ({
    method: init?.method ?? 'GET',
    path: url.replace(BASE, ''),
    body: init?.body,
  }));
const mutationCalls = () => calls().filter((c) => c.method !== 'GET');

const continueButton = () => screen.getByRole('button', { name: 'Continue' });
const availableAs = (username: string, available: boolean) =>
  mockAvailability.mockReturnValue(queryResult({ data: { username, valid: true, available } }));

async function renderScreen(seed: ProfileResponseDTO | null = fresh) {
  const client = createTestQueryClient();
  if (seed) client.setQueryData(queryKeys.me, seed);
  const utils = await renderWithClient(<OnboardingScreen />, client);
  return utils;
}

async function typeUsername(u: string) {
  await fireEvent.changeText(screen.getByLabelText('Username'), u);
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  routerState.reset();
  mockSignOut.mockClear();
  mockAvailability.mockReturnValue(queryResult());
  serve(fresh);
});

describe('OnboardingScreen', () => {
  it('prefills the display name from /me and explains what comes later', async () => {
    await renderScreen();
    expect(screen.getByText('Set up your profile')).toBeOnTheScreen();
    expect(screen.getByLabelText('Display name')).toHaveDisplayValue('Ada Lovelace');
    expect(screen.getByLabelText('Username')).toHaveDisplayValue('');
    expect(screen.getByText("You'll tell us about your taste in a later step.")).toBeOnTheScreen();
    // A username is required.
    expect(continueButton()).toBeDisabled();
  });

  it('offers the existing photo picker', async () => {
    (pickSquarePhoto as jest.Mock).mockResolvedValue({ cancelled: true });
    await renderScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Change photo' }));
    await waitFor(() => expect(pickSquarePhoto).toHaveBeenCalledWith('library'));
  });

  it('signs out', async () => {
    await renderScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
    expect(mockSignOut).toHaveBeenCalled();
  });

  it('shows loading, then load errors with a retry', async () => {
    let resolve!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((r) => (resolve = r)));
    await renderScreen(null);
    expect(screen.getByLabelText('Loading your profile')).toBeOnTheScreen();
    resolve(jsonResponse(503, { message: 'Down for maintenance' }));
    await waitFor(() => expect(screen.getByText("Couldn't load your profile")).toBeOnTheScreen());
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeOnTheScreen();
    serve(fresh);
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByLabelText('Display name')).toHaveDisplayValue('Ada Lovelace'));
  });

  it('blocks Continue while the username is taken', async () => {
    availableAs('popcorn', false);
    await renderScreen();
    await typeUsername('popcorn');
    await waitFor(() => expect(screen.getByTestId('username-status')).toHaveTextContent('Already taken'));
    expect(continueButton()).toBeDisabled();
    await fireEvent.press(continueButton());
    expect(mutationCalls()).toEqual([]);
  });

  it('blocks Continue while the username is invalid', async () => {
    await renderScreen();
    await typeUsername('no');
    expect(screen.getByTestId('username-status')).toHaveTextContent(/3–30 characters/);
    expect(continueButton()).toBeDisabled();
  });

  it('blocks Continue while availability is still being checked', async () => {
    availableAs('popcorn', true);
    await renderScreen();
    await typeUsername('popcorn');
    expect(screen.getByTestId('username-status')).toHaveTextContent('Checking…');
    expect(continueButton()).toBeDisabled();
    await waitFor(() => expect(screen.getByTestId('username-status')).toHaveTextContent('Available'));
    expect(continueButton()).toBeEnabled();
  });

  it('saves, completes onboarding, caches the profile and goes to the tabs', async () => {
    availableAs('popcorn', true);
    const saved = makeProfile({ ...fresh, username: 'popcorn', displayName: 'Ada L' });
    const done = completed({ username: 'popcorn', displayName: 'Ada L' });
    serve(fresh, [
      { method: 'PATCH', path: '/me', status: 200, body: saved },
      { method: 'POST', path: '/me/onboarding/complete', status: 200, body: done },
    ]);
    const { client } = await renderScreen();
    await typeUsername(' Popcorn ');
    await fireEvent.changeText(screen.getByLabelText('Display name'), ' Ada L ');
    await waitFor(() => expect(continueButton()).toBeEnabled());
    await fireEvent.press(continueButton());

    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/'));
    expect(mutationCalls()).toEqual([
      { method: 'PATCH', path: '/me', body: JSON.stringify({ username: 'popcorn', displayName: 'Ada L' }) },
      { method: 'POST', path: '/me/onboarding/complete', body: undefined },
    ]);
    expect(client.getQueryData(queryKeys.me)).toEqual(done);
  });

  it('skips the PATCH when nothing changed (username already chosen)', async () => {
    const named = makeProfile({ ...fresh, username: 'ada' });
    serve(named, [{ method: 'POST', path: '/me/onboarding/complete', status: 200, body: completed({ username: 'ada' }) }]);
    await renderScreen(named);
    expect(screen.getByTestId('username-status')).toHaveTextContent('Your current username');
    await fireEvent.press(continueButton());
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/'));
    expect(mutationCalls().map((c) => `${c.method} ${c.path}`)).toEqual(['POST /me/onboarding/complete']);
  });

  it('shows PATCH fieldErrors inline and does not complete onboarding', async () => {
    availableAs('popcorn', true);
    serve(fresh, [
      {
        method: 'PATCH',
        path: '/me',
        status: 400,
        body: { message: 'Validation failed', fieldErrors: { displayName: 'Display name is too long' } },
      },
    ]);
    await renderScreen();
    await typeUsername('popcorn');
    await waitFor(() => expect(continueButton()).toBeEnabled());
    await fireEvent.press(continueButton());

    await waitFor(() => expect(screen.getByText('Display name is too long')).toBeOnTheScreen());
    expect(screen.queryByTestId('onboarding-error')).toBeNull();
    expect(mutationCalls().map((c) => c.path)).toEqual(['/me']);
    expect(routerState.router.replace).not.toHaveBeenCalled();
  });

  it('shows a username conflict from the PATCH as a general error', async () => {
    availableAs('popcorn', true);
    serve(fresh, [{ method: 'PATCH', path: '/me', status: 409, body: { message: 'That username is already taken.' } }]);
    await renderScreen();
    await typeUsername('popcorn');
    await waitFor(() => expect(continueButton()).toBeEnabled());
    await fireEvent.press(continueButton());
    await waitFor(() =>
      expect(screen.getByTestId('onboarding-error')).toHaveTextContent('That username is already taken.'),
    );
    expect(routerState.router.replace).not.toHaveBeenCalled();
  });

  it('shows a complete-onboarding failure and stays put, then succeeds on retry', async () => {
    availableAs('popcorn', true);
    const saved = makeProfile({ ...fresh, username: 'popcorn' });
    serve(fresh, [
      { method: 'PATCH', path: '/me', status: 200, body: saved },
      { method: 'POST', path: '/me/onboarding/complete', status: 400, body: { message: 'Choose a username first.' } },
    ]);
    await renderScreen();
    await typeUsername('popcorn');
    await waitFor(() => expect(continueButton()).toBeEnabled());
    await fireEvent.press(continueButton());

    await waitFor(() => expect(screen.getByTestId('onboarding-error')).toHaveTextContent('Choose a username first.'));
    expect(routerState.router.replace).not.toHaveBeenCalled();

    serve(saved, [{ method: 'POST', path: '/me/onboarding/complete', status: 200, body: completed({ username: 'popcorn' }) }]);
    await fireEvent.press(continueButton());
    await waitFor(() => expect(routerState.router.replace).toHaveBeenCalledWith('/'));
    expect(screen.queryByTestId('onboarding-error')).toBeNull();
  });
});

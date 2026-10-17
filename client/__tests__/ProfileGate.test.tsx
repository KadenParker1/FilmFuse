jest.mock('expo-router', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return {
    ...require('../test-utils/router').expoRouterMock,
    Redirect: ({ href }: { href: string }) => <Text testID="redirect">{href}</Text>,
  };
});
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));
const mockSignOut = jest.fn(() => Promise.resolve());
jest.mock('../auth/useAuth', () => ({ useAuth: () => ({ signOut: mockSignOut }) }));

import { Text } from 'react-native';
import { act, fireEvent, screen, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { useCompleteOnboarding } from '../api/me';
import { ProfileGate } from '../components/ProfileGate';
import { makeProfile } from '../test-utils/fixtures';
import { jsonResponse, renderWithClient } from '../test-utils/render';

notifyManager.setScheduler((cb) => cb());

const BASE = 'http://localhost:8080/api/v1';
const fetchMock = jest.fn();
const pending = makeProfile({ username: null, onboardingCompleted: false, onboardingCompletedAt: null });
const done = makeProfile({ username: 'ada', onboardingStep: 1, onboardingCompletedAt: '2026-10-16T00:00:00Z' });

beforeEach(() => {
  fetchMock.mockReset();
  mockSignOut.mockClear();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const gate = (
  <ProfileGate>
    <Text>tabs</Text>
  </ProfileGate>
);

describe('ProfileGate', () => {
  it('sends an unfinished profile to onboarding, then lets it through once onboarding completes', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, pending));
    let complete!: ReturnType<typeof useCompleteOnboarding>;
    // Stands in for the onboarding screen's Continue, sharing the gate's query client.
    function Onboarding() {
      complete = useCompleteOnboarding();
      return null;
    }
    await renderWithClient(
      <>
        {gate}
        <Onboarding />
      </>,
    );
    await waitFor(() => expect(screen.getByTestId('redirect')).toHaveTextContent('/onboarding/1'));
    expect(screen.queryByText('tabs')).toBeNull();

    fetchMock.mockResolvedValueOnce(jsonResponse(200, done));
    await act(async () => {
      await complete.mutateAsync();
    });
    expect(fetchMock.mock.calls.at(-1)?.[0]).toBe(`${BASE}/me/onboarding/complete`);
    expect(fetchMock.mock.calls.at(-1)?.[1]).toMatchObject({ method: 'POST' });

    await waitFor(() => expect(screen.getByText('tabs')).toBeOnTheScreen());
    expect(screen.queryByTestId('redirect')).toBeNull();
  });

  it.each([
    [0, '/onboarding/1'],
    [1, '/onboarding/1'],
    [4, '/onboarding/4'],
    [8, '/onboarding/8'],
    [12, '/onboarding/8'],
  ])('resumes a profile saved at step %p on %p', async (step, href) => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { ...pending, onboardingStep: step }));
    await renderWithClient(gate);
    await waitFor(() => expect(screen.getByTestId('redirect')).toHaveTextContent(href));
    expect(screen.queryByText('tabs')).toBeNull();
  });

  it('lets an interim user who completed without taste prefs through', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, { ...done, onboardingStep: 0 }));
    await renderWithClient(gate);
    await waitFor(() => expect(screen.getByText('tabs')).toBeOnTheScreen());
  });

  it('renders the app for an onboarded profile', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(200, done));
    await renderWithClient(gate);
    await waitFor(() => expect(screen.getByText('tabs')).toBeOnTheScreen());
    expect(screen.queryByTestId('redirect')).toBeNull();
  });

  it('shows a retryable error with an escape hatch when /me fails', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(503, { message: 'Unavailable' }));
    await renderWithClient(gate);
    await waitFor(() => expect(screen.getByText("Couldn't load your profile")).toBeOnTheScreen());
    expect(screen.getByText(/Unavailable \(HTTP 503\)/)).toBeOnTheScreen();

    await fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
    expect(mockSignOut).toHaveBeenCalled();

    fetchMock.mockResolvedValueOnce(jsonResponse(200, pending));
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(screen.getByTestId('redirect')).toHaveTextContent('/onboarding/1'));
  });

  it('"Continue anyway" bypasses a failing /me', async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(503, { message: 'Unavailable' }));
    await renderWithClient(gate);
    await waitFor(() => expect(screen.getByText("Couldn't load your profile")).toBeOnTheScreen());
    await fireEvent.press(screen.getByRole('button', { name: 'Continue anyway' }));
    expect(screen.getByText('tabs')).toBeOnTheScreen();
  });
});

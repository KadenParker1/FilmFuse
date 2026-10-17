jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { fireEvent, screen, waitFor, within } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import NotificationSettingsScreen, { NOTIFICATION_PREFS } from '../app/settings/notifications';
import { serveRoutes } from '../test-utils/fetchRoutes';
import { makeNotificationSettings } from '../test-utils/fixtures';
import { renderWithClient } from '../test-utils/render';
import { routerState } from '../test-utils/router';
import type { NotificationSettingsDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  routerState.reset();
  global.fetch = fetchMock as unknown as typeof fetch;
});

const PATH = '/me/settings/notifications';
const defaults = makeNotificationSettings();
const echo = (b: unknown) => b as NotificationSettingsDTO;
const toggle = (label: string) => screen.getByRole('switch', { name: label });

describe('NotificationSettingsScreen — loading', () => {
  it('shows a skeleton while loading', async () => {
    serveRoutes(fetchMock, [{ path: PATH, hang: true }]);
    await renderWithClient(<NotificationSettingsScreen />);
    expect(screen.getByTestId('notifications-loading')).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'Notifications' })).toBeOnTheScreen();
  });

  it('a failed load offers a retry', async () => {
    const api = serveRoutes(fetchMock, [
      { path: PATH, status: 500, body: { message: 'boom' }, times: 1 },
      { path: PATH, body: defaults },
    ]);
    await renderWithClient(<NotificationSettingsScreen />);
    expect(await screen.findByText("Couldn't load your notification settings")).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('switch', { name: 'Comments' })).toBeOnTheScreen();
    expect(api.callsTo('GET', PATH)).toHaveLength(2);
  });

  it('shows every preference with its explanation; digest off by default', async () => {
    serveRoutes(fetchMock, [{ path: PATH, body: defaults }]);
    await renderWithClient(<NotificationSettingsScreen />);
    await screen.findByRole('switch', { name: 'Friend requests' });
    expect(NOTIFICATION_PREFS.map((p) => p.key)).toEqual([
      'friendRequests',
      'groupInvites',
      'sessionStarted',
      'comments',
      'weeklyDigest',
    ]);
    NOTIFICATION_PREFS.forEach(({ key, label, description }) => {
      const row = screen.getByTestId(`pref-${key}`);
      expect(within(row).getByText(label)).toBeOnTheScreen();
      expect(within(row).getByText(description)).toBeOnTheScreen();
      expect(toggle(label).props.accessibilityHint).toBe(description);
    });
    expect(toggle('Friend requests')).toBeChecked();
    expect(toggle('Group invites')).toBeChecked();
    expect(toggle('Movie sessions')).toBeChecked();
    expect(toggle('Comments')).toBeChecked();
    expect(toggle('Weekly digest')).not.toBeChecked();
    expect(screen.getByText(/Off unless you turn it on/)).toBeOnTheScreen();
  });

  it('Back falls back to the profile', async () => {
    serveRoutes(fetchMock, [{ path: PATH, body: defaults }]);
    routerState.router.canGoBack.mockReturnValue(false);
    await renderWithClient(<NotificationSettingsScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Back' }));
    expect(routerState.router.replace).toHaveBeenCalledWith('/profile');
  });
});

describe('NotificationSettingsScreen — saving', () => {
  it.each(NOTIFICATION_PREFS.map((p) => [p.label, p.key] as const))(
    'toggling %s PUTs the whole object with just that flipped',
    async (label, key) => {
      const api = serveRoutes(fetchMock, [
        { method: 'PUT', path: PATH, body: echo },
        { path: PATH, body: defaults },
      ]);
      await renderWithClient(<NotificationSettingsScreen />);
      await screen.findByRole('switch', { name: label });
      await fireEvent(toggle(label), 'valueChange', !defaults[key]);
      await waitFor(() => expect(api.callsTo('PUT', PATH)).toHaveLength(1));
      expect(api.callsTo('PUT', PATH)[0]!.body).toEqual({ ...defaults, [key]: !defaults[key] });
      expect(await screen.findByText('Saved.')).toBeOnTheScreen();
    },
  );

  it('flips at once and locks the switches while saving', async () => {
    serveRoutes(fetchMock, [
      { method: 'PUT', path: PATH, hang: true },
      { path: PATH, body: defaults },
    ]);
    await renderWithClient(<NotificationSettingsScreen />);
    await screen.findByRole('switch', { name: 'Weekly digest' });
    await fireEvent(toggle('Weekly digest'), 'valueChange', true);
    expect(toggle('Weekly digest')).toBeChecked();
    expect(toggle('Comments')).toBeDisabled();
  });

  it('a failed save flips back and says so', async () => {
    serveRoutes(fetchMock, [
      { method: 'PUT', path: PATH, status: 500, body: { message: 'boom' } },
      { path: PATH, body: defaults },
    ]);
    await renderWithClient(<NotificationSettingsScreen />);
    await screen.findByRole('switch', { name: 'Comments' });
    await fireEvent(toggle('Comments'), 'valueChange', false);
    expect(await screen.findByRole('alert')).toHaveTextContent(/Couldn't save that change/);
    expect(toggle('Comments')).toBeChecked();
    expect(toggle('Comments')).toBeEnabled();
    expect(screen.queryByText('Saved.')).toBeNull();
  });
});

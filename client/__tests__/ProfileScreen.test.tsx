jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
const mockSignOut = jest.fn();
jest.mock('../auth/useAuth', () => ({
  useAuth: () => ({ user: { email: 'ada@example.com' }, signOut: mockSignOut }),
}));
jest.mock('../api/me', () => ({ useMe: jest.fn(), useDeleteAccount: jest.fn() }));
// The taste and ratings sections have their own tests.
jest.mock('../components/taste/TasteSection', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return { TasteSection: () => <Text testID="taste-section-stub">taste</Text> };
});
jest.mock('../components/rating/RatingsList', () => {
  const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
  return { RatingsList: () => <Text testID="ratings-list-stub">ratings</Text> };
});

import { fireEvent, render, screen } from '@testing-library/react-native';
import ProfileScreen from '../app/(tabs)/profile';
import { useDeleteAccount, useMe } from '../api/me';
import { NetworkError } from '../lib/apiClient';
import { makeProfile } from '../test-utils/fixtures';
import { queryResult } from '../test-utils/queryResults';
import { routerState } from '../test-utils/router';

const mockUseMe = useMe as jest.Mock;
const mockUseDelete = useDeleteAccount as jest.Mock;

beforeEach(() => {
  routerState.reset();
  mockSignOut.mockReset();
  mockUseDelete.mockReturnValue({ mutate: jest.fn(), reset: jest.fn(), isPending: false, isError: false, error: null });
});

describe('ProfileScreen', () => {
  it('shows the taste section and your ratings', async () => {
    mockUseMe.mockReturnValue(queryResult({ data: makeProfile() }));
    await render(<ProfileScreen />);
    expect(screen.getByTestId('taste-section-stub')).toBeOnTheScreen();
    expect(screen.getByTestId('ratings-list-stub')).toBeOnTheScreen();
    expect(screen.getByRole('button', { name: 'Comfort settings' })).toBeOnTheScreen();
  });

  it('shows the photo, name, username and bio', async () => {
    mockUseMe.mockReturnValue(queryResult({ data: makeProfile({ profileImageUrl: 'https://x/ada.jpg' }) }));
    await render(<ProfileScreen />);
    expect(screen.getByTestId('avatar-image')).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'Ada Lovelace' })).toBeOnTheScreen();
    expect(screen.getByText('@ada')).toBeOnTheScreen();
    expect(screen.getByText('ada@example.com')).toBeOnTheScreen();
    expect(screen.getByTestId('profile-bio')).toHaveTextContent('Loves heist films.');
  });

  it('falls back to initials without a photo', async () => {
    mockUseMe.mockReturnValue(queryResult({ data: makeProfile({ profileImageUrl: null, bio: null }) }));
    await render(<ProfileScreen />);
    expect(screen.getByTestId('avatar-initials')).toHaveTextContent('AL');
    expect(screen.queryByTestId('profile-bio')).toBeNull();
  });

  it('shows a loading skeleton', async () => {
    mockUseMe.mockReturnValue(queryResult());
    await render(<ProfileScreen />);
    expect(screen.getByTestId('profile-loading')).toBeOnTheScreen();
  });

  it('shows an error with retry', async () => {
    const refetch = jest.fn();
    mockUseMe.mockReturnValue(queryResult({ isError: true, isPending: false, error: new NetworkError(), refetch }));
    await render(<ProfileScreen />);
    expect(screen.getByText('Profile unavailable')).toBeOnTheScreen();
    await fireEvent.press(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalled();
  });

  it('navigates to edit, signs out, and opens the delete sheet', async () => {
    mockUseMe.mockReturnValue(queryResult({ data: makeProfile() }));
    await render(<ProfileScreen />);
    await fireEvent.press(screen.getByRole('button', { name: 'Edit profile' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/profile/edit');
    await fireEvent.press(screen.getByRole('button', { name: 'Comfort settings' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/settings/comfort');
    await fireEvent.press(screen.getByRole('button', { name: 'Notifications' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/settings/notifications');
    await fireEvent.press(screen.getByRole('button', { name: 'Blocked people' }));
    expect(routerState.router.push).toHaveBeenCalledWith('/settings/blocked');
    await fireEvent.press(screen.getByRole('button', { name: 'Sign out' }));
    expect(mockSignOut).toHaveBeenCalled();
    expect(screen.queryByText('Delete your account?')).toBeNull();
    await fireEvent.press(screen.getByRole('button', { name: 'Delete account' }));
    expect(screen.getByText('Delete your account?')).toBeOnTheScreen();
  });
});

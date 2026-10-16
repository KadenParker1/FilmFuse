jest.mock('expo-router', () => {
  const { Text, View } = jest.requireActual<typeof import('react-native')>('react-native');
  const React = jest.requireActual<typeof import('react')>('react');
  const Tabs = ({ children }: { children: React.ReactNode }) => React.createElement(View, null, children);
  Tabs.Screen = ({ name, options }: { name: string; options: Record<string, unknown> }) =>
    React.createElement(
      Text,
      { testID: `tab-${name}`, accessibilityLabel: String(options.tabBarAccessibilityLabel) },
      `badge:${options.tabBarBadge ?? 'none'}`,
    );
  return { Tabs };
});
jest.mock('../components/ProfileGate', () => ({ ProfileGate: ({ children }: { children: unknown }) => children }));
jest.mock('../components/RateFab', () => ({ RateFab: () => null }));
jest.mock('../components/social/PendingLinkRedeemer', () => ({
  PendingLinkRedeemer: () => {
    const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
    return <Text>redeemer</Text>;
  },
}));
jest.mock('../api/social', () => ({ useFriendRequests: jest.fn() }));

import { render, screen } from '@testing-library/react-native';
import TabsLayout from '../app/(tabs)/_layout';
import { useFriendRequests } from '../api/social';
import { makeRequest } from '../test-utils/fixtures';
import { queryResult } from '../test-utils/queryResults';

const mockRequests = useFriendRequests as jest.Mock;
const requests = (n: number) =>
  queryResult({ data: Array.from({ length: n }, (_, i) => makeRequest({ id: `r${i}` })) });

describe('TabsLayout', () => {
  it('badges the Social tab with the incoming request count', async () => {
    mockRequests.mockReturnValue(requests(2));
    await render(<TabsLayout />);
    expect(mockRequests).toHaveBeenCalledWith('INCOMING');
    expect(screen.getByTestId('tab-index')).toHaveTextContent('badge:2');
    expect(screen.getByLabelText('Social, 2 friend requests')).toBeOnTheScreen();
    expect(screen.getByTestId('tab-groups')).toHaveTextContent('badge:none');
    expect(screen.getByTestId('tab-profile')).toHaveTextContent('badge:none');
  });

  it('caps the badge at 9+ and uses the singular for one', async () => {
    mockRequests.mockReturnValue(requests(14));
    const { rerender } = await render(<TabsLayout />);
    expect(screen.getByTestId('tab-index')).toHaveTextContent('badge:9+');
    mockRequests.mockReturnValue(requests(1));
    await rerender(<TabsLayout />);
    expect(screen.getByLabelText('Social, 1 friend request')).toBeOnTheScreen();
  });

  it('has no badge while loading or with no requests', async () => {
    mockRequests.mockReturnValue(queryResult());
    const { rerender } = await render(<TabsLayout />);
    expect(screen.getByTestId('tab-index')).toHaveTextContent('badge:none');
    expect(screen.getByLabelText('Social')).toBeOnTheScreen();
    mockRequests.mockReturnValue(requests(0));
    await rerender(<TabsLayout />);
    expect(screen.getByTestId('tab-index')).toHaveTextContent('badge:none');
  });

  it('mounts the pending-link redeemer', async () => {
    mockRequests.mockReturnValue(requests(0));
    await render(<TabsLayout />);
    expect(screen.getByText('redeemer')).toBeOnTheScreen();
  });
});

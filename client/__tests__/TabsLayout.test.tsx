let mockWidth = 390;
jest.mock('react-native/Libraries/Utilities/useWindowDimensions', () => ({
  __esModule: true,
  default: () => ({ width: mockWidth, height: 800, scale: 2, fontScale: 1 }),
}));
const mockNavigate = jest.fn();
const mockEmit = jest.fn(() => ({ defaultPrevented: false }));
let mockTabsProps: Record<string, unknown> = {};
jest.mock('expo-router', () => {
  const { Text, View } = jest.requireActual<typeof import('react-native')>('react-native');
  const React = jest.requireActual<typeof import('react')>('react');
  // Renders the screens, plus the custom tabBar (if any) with a navigator state focused on Discover.
  const Tabs = ({ children, tabBar, ...rest }: { children: React.ReactNode; tabBar?: (p: unknown) => React.ReactNode }) => {
    mockTabsProps = { tabBar, ...rest };
    const routes = ['index', 'groups', 'discover', 'profile'].map((name) => ({ key: `${name}-key`, name }));
    return React.createElement(
      View,
      null,
      children,
      tabBar ? tabBar({ state: { index: 2, routes }, navigation: { emit: mockEmit, navigate: mockNavigate }, descriptors: {} }) : null,
    );
  };
  Tabs.Screen = ({ name, options }: { name: string; options: Record<string, unknown> }) =>
    React.createElement(
      Text,
      { testID: `tab-${name}`, accessibilityLabel: String(options.tabBarAccessibilityLabel) },
      `badge:${options.tabBarBadge ?? 'none'}`,
    );
  return { Tabs };
});
jest.mock('../components/ProfileGate', () => ({ ProfileGate: ({ children }: { children: unknown }) => children }));
jest.mock('../components/RateFab', () => ({
  RateFab: () => {
    const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
    return <Text testID="rate-fab">fab</Text>;
  },
}));
const mockOpenRatePicker = jest.fn();
jest.mock('../components/rating/QuickRateProvider', () => ({
  useQuickRate: () => ({ openRatePicker: mockOpenRatePicker, openQuickRate: jest.fn(), showTasteUpdating: jest.fn() }),
}));
jest.mock('../api/me', () => ({ useMe: jest.fn() }));
jest.mock('../api/taste', () => ({ useTasteProfile: jest.fn() }));
jest.mock('../components/social/PendingLinkRedeemer', () => ({
  PendingLinkRedeemer: () => {
    const { Text } = jest.requireActual<typeof import('react-native')>('react-native');
    return <Text>redeemer</Text>;
  },
}));
jest.mock('../api/social', () => ({ useFriendRequests: jest.fn() }));

import { fireEvent, render, screen } from '@testing-library/react-native';
import { useMe } from '../api/me';
import { useTasteProfile } from '../api/taste';
import { WIDE_BREAKPOINT } from '../theme/tokens';
import TabsLayout from '../app/(tabs)/_layout';
import { useFriendRequests } from '../api/social';
import { makeProfile, makeRequest, makeTasteProfile } from '../test-utils/fixtures';
import { queryResult } from '../test-utils/queryResults';

const mockRequests = useFriendRequests as jest.Mock;
beforeEach(() => {
  mockWidth = 390;
  (useMe as jest.Mock).mockReturnValue(queryResult({ data: makeProfile({ displayName: 'Kaden Parker' }) }));
  (useTasteProfile as jest.Mock).mockReturnValue(queryResult({ data: makeTasteProfile({ persona: 'Cerebral Action Explorer' }) }));
  mockNavigate.mockClear();
  mockEmit.mockClear();
  mockOpenRatePicker.mockClear();
});
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

describe('TabsLayout — phones', () => {
  it('keeps the bottom tab bar and the floating Rate button', async () => {
    mockRequests.mockReturnValue(requests(0));
    await render(<TabsLayout />);
    expect(mockTabsProps.tabBar).toBeUndefined();
    expect((mockTabsProps.screenOptions as { tabBarPosition: string }).tabBarPosition).toBe('bottom');
    expect(screen.getByTestId('rate-fab')).toBeOnTheScreen();
    expect(screen.queryByTestId('nav-rail')).toBeNull();
  });
});

describe('TabsLayout — wide screens: nav rail on the right', () => {
  beforeEach(() => {
    mockWidth = WIDE_BREAKPOINT;
  });

  it('shows the rail on the right with brand, tabs, Rate and the mini profile, and no floating button', async () => {
    mockRequests.mockReturnValue(requests(2));
    await render(<TabsLayout />);
    expect((mockTabsProps.screenOptions as { tabBarPosition: string }).tabBarPosition).toBe('right');
    expect(screen.getByTestId('nav-rail')).toBeOnTheScreen();
    expect(screen.getByRole('header', { name: 'FilmFuse' })).toBeOnTheScreen();
    for (const name of ['Groups', 'Discover', 'Profile']) expect(screen.getByRole('tab', { name })).toBeOnTheScreen();
    expect(screen.getByRole('tab', { name: 'Social, 2 friend requests' })).toBeOnTheScreen();
    expect(screen.getByTestId('rail-badge-index')).toHaveTextContent('2');
    expect(screen.getByRole('button', { name: 'Rate a movie' })).toBeOnTheScreen();
    expect(screen.queryByTestId('rate-fab')).toBeNull();
    expect(screen.getByTestId('rail-me')).toHaveTextContent(/Kaden Parker/);
    expect(screen.getByTestId('rail-persona')).toHaveTextContent('Cerebral Action Explorer');
  });

  it('marks the focused tab and navigates to others via tabPress', async () => {
    mockRequests.mockReturnValue(requests(0));
    await render(<TabsLayout />);
    expect(screen.getByRole('tab', { name: 'Discover' })).toBeSelected();
    expect(screen.getByRole('tab', { name: 'Groups' })).not.toBeSelected();
    await fireEvent.press(screen.getByRole('tab', { name: 'Groups' }));
    expect(mockEmit).toHaveBeenCalledWith({ type: 'tabPress', target: 'groups-key', canPreventDefault: true });
    expect(mockNavigate).toHaveBeenCalledWith('groups', undefined);
    // Pressing the focused tab doesn't navigate.
    mockNavigate.mockClear();
    await fireEvent.press(screen.getByRole('tab', { name: 'Discover' }));
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('Rate opens the movie picker; the mini profile opens Profile', async () => {
    mockRequests.mockReturnValue(requests(0));
    await render(<TabsLayout />);
    await fireEvent.press(screen.getByRole('button', { name: 'Rate a movie' }));
    expect(mockOpenRatePicker).toHaveBeenCalled();
    await fireEvent.press(screen.getByTestId('rail-me'));
    expect(mockNavigate).toHaveBeenCalledWith('profile', undefined);
  });

  it('copes with no persona yet and caps the badge', async () => {
    (useTasteProfile as jest.Mock).mockReturnValue(queryResult({ data: makeTasteProfile({ persona: null }) }));
    mockRequests.mockReturnValue(requests(12));
    await render(<TabsLayout />);
    expect(screen.queryByTestId('rail-persona')).toBeNull();
    expect(screen.getByTestId('rail-badge-index')).toHaveTextContent('9+');
  });

  it('just below the breakpoint is still a phone layout', async () => {
    mockWidth = WIDE_BREAKPOINT - 1;
    mockRequests.mockReturnValue(requests(0));
    await render(<TabsLayout />);
    expect(screen.queryByTestId('nav-rail')).toBeNull();
    expect(screen.getByTestId('rate-fab')).toBeOnTheScreen();
  });
});

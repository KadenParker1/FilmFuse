import { useWindowDimensions, View } from 'react-native';
import { Tabs } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useGroupsBadgeCount } from '../../api/groupsBadge';
import { useFriendRequests } from '../../api/social';
import { ProfileGate } from '../../components/ProfileGate';
import { RateFab } from '../../components/RateFab';
import { NavRail, type NavRailProps } from '../../components/nav/NavRail';
import { NAV_TABS, tabAccessibilityLabel, type TabBadges } from '../../components/nav/navTabs';
import { PendingLinkRedeemer } from '../../components/social/PendingLinkRedeemer';
import { badgeCount } from '../../lib/format';
import { fonts, WIDE_BREAKPOINT } from '../../theme/tokens';
import { useTheme } from '../../theme/useTheme';

/** Approximate bottom tab bar height, used to float "+ Rate" above it. */
const TAB_BAR_HEIGHT = 60;

export default function TabsLayout() {
  return (
    <ProfileGate>
      <TabsShell />
      <PendingLinkRedeemer />
    </ProfileGate>
  );
}

/**
 * Rendered only for onboarded users (inside ProfileGate), so its queries never run before that.
 * Phones: bottom tabs + the floating Rate button. Wide screens (≥ WIDE_BREAKPOINT): the nav rail on
 * the right, which carries the Rate button itself, so the floating one is hidden.
 */
function TabsShell() {
  const { width } = useWindowDimensions();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const wide = width >= WIDE_BREAKPOINT;
  const incoming = useFriendRequests('INCOMING');
  const incomingCount = incoming.data?.length ?? 0;
  const groupsCount = useGroupsBadgeCount();
  const badges: TabBadges = { index: incomingCount, groups: groupsCount };

  return (
    <View className="flex-1">
      <Tabs
        tabBar={
          wide
            ? (props) => <NavRail {...(props as unknown as Omit<NavRailProps, 'badges'>)} badges={badges} />
            : undefined
        }
        screenOptions={{
          headerShown: false,
          tabBarPosition: wide ? 'right' : 'bottom',
          tabBarActiveTintColor: colors.accentInk,
          tabBarInactiveTintColor: colors.muted,
          tabBarStyle: {
            backgroundColor: colors.surface,
            borderTopColor: colors.border,
            paddingTop: 6,
            height: TAB_BAR_HEIGHT + insets.bottom,
          },
          tabBarLabelStyle: { fontFamily: fonts.bodySemibold, fontSize: 11 },
          tabBarBadgeStyle: { backgroundColor: colors.accent2, color: colors.onAccent2, fontFamily: fonts.bodyBold },
          sceneStyle: { backgroundColor: colors.bg },
        }}
      >
        {NAV_TABS.map((tab) => {
          // Social: pending friend requests. Groups: open sessions I haven't voted in + group invites.
          const count = badges[tab.name] ?? 0;
          return (
            <Tabs.Screen
              key={tab.name}
              name={tab.name}
              options={{
                title: tab.title,
                tabBarBadge: badgeCount(count),
                tabBarAccessibilityLabel: tabAccessibilityLabel(tab, count),
                tabBarIcon: ({ focused, color, size }) => (
                  <Ionicons name={focused ? tab.iconFocused : tab.icon} size={size} color={color as string} />
                ),
              }}
            />
          );
        })}
      </Tabs>
      {wide ? null : <RateFab bottomOffset={TAB_BAR_HEIGHT + insets.bottom + 14} rightOffset={insets.right + 16} />}
    </View>
  );
}

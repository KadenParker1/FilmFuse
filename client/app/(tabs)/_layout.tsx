import type { ComponentProps } from 'react';
import { useWindowDimensions, View } from 'react-native';
import { Tabs } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ProfileGate } from '../../components/ProfileGate';
import { RateFab } from '../../components/RateFab';
import { fonts, WIDE_BREAKPOINT } from '../../theme/tokens';
import { useTheme } from '../../theme/useTheme';

type IconName = ComponentProps<typeof Ionicons>['name'];

const TABS: { name: string; title: string; icon: IconName; iconFocused: IconName }[] = [
  { name: 'index', title: 'Social', icon: 'people-outline', iconFocused: 'people' },
  { name: 'groups', title: 'Groups', icon: 'albums-outline', iconFocused: 'albums' },
  { name: 'discover', title: 'Discover', icon: 'compass-outline', iconFocused: 'compass' },
  { name: 'profile', title: 'Profile', icon: 'person-circle-outline', iconFocused: 'person-circle' },
];

/** Approximate default bottom tab bar height, used to float "+ Rate" above it. */
const TAB_BAR_HEIGHT = 56;

export default function TabsLayout() {
  const { width } = useWindowDimensions();
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const wide = width >= WIDE_BREAKPOINT;

  return (
    <ProfileGate>
      <View className="flex-1">
        <Tabs
          screenOptions={{
            headerShown: false,
            tabBarPosition: wide ? 'left' : 'bottom',
            tabBarLabelPosition: wide ? 'beside-icon' : 'below-icon',
            tabBarActiveTintColor: colors.gold,
            tabBarInactiveTintColor: colors.textMuted,
            tabBarActiveBackgroundColor: wide ? colors.surfaceRaised : undefined,
            tabBarStyle: {
              backgroundColor: colors.surface,
              borderTopColor: colors.border,
              borderRightColor: colors.border,
              ...(wide ? { width: 240, paddingTop: 24 } : {}),
            },
            tabBarLabelStyle: { fontFamily: fonts.bodyMedium, fontSize: wide ? 15 : 12 },
            sceneStyle: { backgroundColor: colors.bg },
          }}
        >
          {TABS.map((tab) => (
            <Tabs.Screen
              key={tab.name}
              name={tab.name}
              options={{
                title: tab.title,
                tabBarAccessibilityLabel: tab.title,
                tabBarIcon: ({ focused, color, size }) => (
                  <Ionicons name={focused ? tab.iconFocused : tab.icon} size={size} color={color as string} />
                ),
              }}
            />
          ))}
        </Tabs>
        <RateFab bottomOffset={wide ? 32 : TAB_BAR_HEIGHT + insets.bottom + 16} rightOffset={wide ? 32 : 20} />
      </View>
    </ProfileGate>
  );
}

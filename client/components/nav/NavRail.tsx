import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useMe } from '../../api/me';
import { useTasteProfile } from '../../api/taste';
import { badgeCount, pluralize } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import { useQuickRate } from '../rating/QuickRateProvider';
import { Avatar } from '../ui';
import { NAV_TABS } from './navTabs';

/** The slice of the tab navigator's tabBar props the rail needs. */
export type NavRailProps = {
  state: { index: number; routes: { key: string; name: string; params?: object }[] };
  navigation: {
    emit: (e: { type: 'tabPress'; target: string; canPreventDefault: true }) => { defaultPrevented: boolean };
    navigate: (name: string, params?: object) => void;
  };
  /** Pending friend requests, badged on Social. */
  incomingCount: number;
};

export const NAV_RAIL_WIDTH = 232;

/** Avatar, display name and taste persona at the foot of the rail; opens Profile. */
function MiniProfile({ onPress }: { onPress: () => void }) {
  const me = useMe();
  const taste = useTasteProfile();
  const profile = me.data;
  const name = profile ? profile.displayName || profile.username || 'You' : 'You';
  const persona = taste.data?.persona ?? null;
  return (
    <Pressable
      testID="rail-me"
      accessibilityRole="button"
      accessibilityLabel={persona ? `${name}, ${persona}. Open your profile` : `${name}. Open your profile`}
      onPress={onPress}
      className="mt-2 flex-row items-center gap-2.5 border-t border-border px-2 pt-3 active:opacity-70"
    >
      <Avatar uri={profile?.profileImageUrl} displayName={profile?.displayName} username={profile?.username} size={32} />
      <View className="flex-1">
        <Text className="font-body-bold text-sm text-text" numberOfLines={1}>
          {name}
        </Text>
        {persona ? (
          <Text testID="rail-persona" className="font-body text-xs text-muted" numberOfLines={1}>
            {persona}
          </Text>
        ) : null}
      </View>
    </Pressable>
  );
}

/**
 * Wide screens: the navigation rail on the right - brand at the top, the four tabs with an active
 * pill (Social carries the friend-request badge), then "Rate a movie" and your mini profile at the
 * foot. It replaces both the bottom tab bar and the floating Rate button.
 */
export function NavRail({ state, navigation, incomingCount }: NavRailProps) {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const { openRatePicker } = useQuickRate();

  const go = (name: string) => {
    const route = state.routes.find((r) => r.name === name);
    if (!route) return;
    const focused = state.routes[state.index]?.key === route.key;
    const event = navigation.emit({ type: 'tabPress', target: route.key, canPreventDefault: true });
    if (!focused && !event.defaultPrevented) navigation.navigate(route.name, route.params);
  };

  return (
    <View
      testID="nav-rail"
      accessibilityRole="tablist"
      accessibilityLabel="Main navigation"
      className="border-l border-border bg-surface px-3.5"
      style={{ width: NAV_RAIL_WIDTH, paddingTop: insets.top + 22, paddingBottom: insets.bottom + 18 }}
    >
      <View className="flex-row items-center gap-2.5 px-2 pb-5">
        <View className="h-8 w-8 items-center justify-center rounded-[10px] bg-accent">
          <Ionicons name="play" size={16} color={colors.onAccent} />
        </View>
        <Text accessibilityRole="header" className="font-display-bold text-lg tracking-tight text-text">
          FilmFuse
        </Text>
      </View>

      <View className="gap-1">
        {NAV_TABS.map((tab) => {
          const route = state.routes.find((r) => r.name === tab.name);
          const focused = !!route && state.routes[state.index]?.key === route.key;
          const count = tab.name === 'index' ? incomingCount : 0;
          const badge = badgeCount(count);
          return (
            <Pressable
              key={tab.name}
              testID={`rail-${tab.name}`}
              accessibilityRole="tab"
              accessibilityLabel={count > 0 ? `${tab.title}, ${pluralize(count, 'friend request')}` : tab.title}
              accessibilityState={{ selected: focused }}
              onPress={() => go(tab.name)}
              className={`min-h-11 flex-row items-center gap-3 rounded-xl px-3 py-2.5 active:opacity-80 ${
                focused ? 'bg-accent-soft' : ''
              }`}
            >
              <Ionicons
                name={focused ? tab.iconFocused : tab.icon}
                size={20}
                color={focused ? colors.accentInk : colors.muted}
              />
              <Text className={`flex-1 font-body-semibold text-sm ${focused ? 'text-accent-ink' : 'text-muted'}`}>
                {tab.title}
              </Text>
              {badge != null ? (
                <View testID={`rail-badge-${tab.name}`} className="rounded-full bg-accent2 px-2 py-px">
                  <Text className="font-body-bold text-[11px] text-on-accent2">{badge}</Text>
                </View>
              ) : null}
            </Pressable>
          );
        })}
      </View>

      <View className="flex-1" />

      <Pressable
        testID="rail-rate"
        accessibilityRole="button"
        accessibilityLabel="Rate a movie"
        onPress={openRatePicker}
        className="min-h-12 flex-row items-center justify-center gap-2 rounded-2xl bg-accent px-3 py-3 active:opacity-80"
      >
        <Ionicons name="add" size={20} color={colors.onAccent} />
        <Text className="font-body-bold text-sm text-on-accent">Rate a movie</Text>
      </Pressable>
      <MiniProfile onPress={() => go('profile')} />
    </View>
  );
}

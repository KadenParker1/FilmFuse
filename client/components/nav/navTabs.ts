import type { ComponentProps } from 'react';
import type Ionicons from '@expo/vector-icons/Ionicons';
import { pluralize } from '../../lib/format';

export type IconName = ComponentProps<typeof Ionicons>['name'];

export type NavTab = { name: string; title: string; icon: IconName; iconFocused: IconName };

/** The four tabs, in order, shared by the bottom tab bar (phones) and the nav rail (wide screens). */
export const NAV_TABS: NavTab[] = [
  { name: 'index', title: 'Social', icon: 'people-outline', iconFocused: 'people' },
  { name: 'groups', title: 'Groups', icon: 'albums-outline', iconFocused: 'albums' },
  { name: 'discover', title: 'Discover', icon: 'compass-outline', iconFocused: 'compass' },
  { name: 'profile', title: 'Profile', icon: 'person-circle-outline', iconFocused: 'person-circle' },
];

/** Badge counts by tab name (missing = no badge). Social: friend requests; Groups: sessions to vote in + group invites. */
export type TabBadges = Partial<Record<string, number>>;

/** What a screen reader hears for a tab with `count` waiting items. */
export function tabAccessibilityLabel(tab: NavTab, count: number): string {
  if (count <= 0) return tab.title;
  if (tab.name === 'index') return `${tab.title}, ${pluralize(count, 'friend request')}`;
  if (tab.name === 'groups') return `${tab.title}, ${count} waiting on you`;
  return `${tab.title}, ${count} new`;
}

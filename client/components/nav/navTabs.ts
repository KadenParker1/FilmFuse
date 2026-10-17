import type { ComponentProps } from 'react';
import type Ionicons from '@expo/vector-icons/Ionicons';

export type IconName = ComponentProps<typeof Ionicons>['name'];

export type NavTab = { name: string; title: string; icon: IconName; iconFocused: IconName };

/** The four tabs, in order, shared by the bottom tab bar (phones) and the nav rail (wide screens). */
export const NAV_TABS: NavTab[] = [
  { name: 'index', title: 'Social', icon: 'people-outline', iconFocused: 'people' },
  { name: 'groups', title: 'Groups', icon: 'albums-outline', iconFocused: 'albums' },
  { name: 'discover', title: 'Discover', icon: 'compass-outline', iconFocused: 'compass' },
  { name: 'profile', title: 'Profile', icon: 'person-circle-outline', iconFocused: 'person-circle' },
];

import type { ReactNode } from 'react';
import { Pressable, ScrollView, Text, View } from 'react-native';
import { useRouter, type Href } from 'expo-router';
import { BackButton } from '../BackButton';
import { Heading, Screen } from '../ui';

export type AdminSection = 'overview' | 'catalog' | 'reports' | 'audit';

export const ADMIN_SECTIONS: { value: AdminSection; label: string; href: Href }[] = [
  { value: 'overview', label: 'Overview', href: '/admin' },
  { value: 'catalog', label: 'Catalog & jobs', href: '/admin/catalog' },
  { value: 'reports', label: 'Reports', href: '/admin/reports' },
  { value: 'audit', label: 'Audit log', href: '/admin/audit' },
];

/** Shared chrome of every admin page: back to Profile, the title and the section tabs (scrollable on phones). */
export function AdminFrame({ current, title, children }: { current: AdminSection; title: string; children: ReactNode }) {
  const router = useRouter();
  return (
    <Screen scroll wide>
      <View className="mb-4 flex-row items-center gap-3">
        <BackButton fallback="/profile" />
        <View className="flex-1">
          <Text className="font-body-semibold text-xs uppercase tracking-widest text-accent2">Admin</Text>
          <Heading size="md">{title}</Heading>
        </View>
      </View>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} className="mb-6 flex-grow-0">
        <View accessibilityRole="tablist" accessibilityLabel="Admin sections" className="flex-row gap-2">
          {ADMIN_SECTIONS.map((section) => {
            const selected = section.value === current;
            return (
              <Pressable
                key={section.value}
                accessibilityRole="tab"
                accessibilityLabel={section.label}
                accessibilityState={{ selected }}
                onPress={() => {
                  if (!selected) router.replace(section.href);
                }}
                className={`min-h-10 justify-center rounded-full border px-4 py-2 active:opacity-80 ${
                  selected ? 'border-text bg-text' : 'border-border bg-surface'
                }`}
              >
                <Text className={`font-body-semibold text-sm ${selected ? 'text-bg' : 'text-muted'}`}>{section.label}</Text>
              </Pressable>
            );
          })}
        </View>
      </ScrollView>
      <View className="gap-6 pb-16">{children}</View>
    </Screen>
  );
}

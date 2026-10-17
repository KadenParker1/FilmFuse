import type { ComponentProps } from 'react';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { BackButton } from '../../components/BackButton';
import { Body, Card, Heading, Screen } from '../../components/ui';
import type { AppearancePreference } from '../../lib/appearancePreference';
import { useAppearance } from '../../theme/AppearanceProvider';
import { useTheme } from '../../theme/useTheme';

const OPTIONS: {
  value: AppearancePreference;
  label: string;
  description: string;
  icon: ComponentProps<typeof Ionicons>['name'];
}[] = [
  { value: 'dark', label: 'Dark', description: 'The default. Easiest on the eyes in a dark room.', icon: 'moon' },
  { value: 'light', label: 'Light', description: 'Bright surfaces for daytime.', icon: 'sunny' },
  { value: 'system', label: 'System', description: 'Follow your device’s setting.', icon: 'phone-portrait-outline' },
];

/** Settings → Appearance: Dark (default) / Light / System, saved on this device. */
export default function AppearanceSettingsScreen() {
  const { preference, setPreference } = useAppearance();
  const { colors } = useTheme();

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/profile" />
        <Heading size="md">Appearance</Heading>
      </View>
      <Card className="gap-1 p-2">
        <View accessibilityRole="radiogroup" accessibilityLabel="Appearance">
          {OPTIONS.map((o) => {
            const selected = preference === o.value;
            return (
              <Pressable
                key={o.value}
                accessibilityRole="radio"
                accessibilityLabel={o.label}
                accessibilityHint={o.description}
                accessibilityState={{ checked: selected }}
                onPress={() => setPreference(o.value)}
                className={`min-h-14 flex-row items-center gap-3 rounded-xl px-3 py-3 active:opacity-80 ${
                  selected ? 'bg-accent-soft' : ''
                }`}
              >
                <Ionicons name={o.icon} size={20} color={selected ? colors.accentInk : colors.muted} />
                <View className="flex-1">
                  <Text className={`font-body-bold text-base ${selected ? 'text-accent-ink' : 'text-text'}`}>{o.label}</Text>
                  <Text className="font-body text-sm text-muted">{o.description}</Text>
                </View>
                {selected ? <Ionicons name="checkmark-circle" size={22} color={colors.accentInk} /> : null}
              </Pressable>
            );
          })}
        </View>
      </Card>
      <Body muted className="mt-4 text-sm">
        Saved on this device only.
      </Body>
    </Screen>
  );
}

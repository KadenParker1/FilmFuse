import type { ComponentProps } from 'react';
import { View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../../theme/useTheme';
import { Body, Heading } from './Text';

type Props = {
  icon: ComponentProps<typeof Ionicons>['name'];
  title: string;
  message: string;
};

export function EmptyState({ icon, title, message }: Props) {
  const { colors } = useTheme();
  return (
    <View className="flex-1 items-center justify-center gap-3 px-6 py-12">
      <View className="mb-2 h-20 w-20 items-center justify-center rounded-3xl bg-accent-soft">
        <Ionicons name={icon} size={36} color={colors.accentInk} />
      </View>
      <Heading size="sm" className="text-center">
        {title}
      </Heading>
      <Body muted className="max-w-sm text-center">
        {message}
      </Body>
    </View>
  );
}

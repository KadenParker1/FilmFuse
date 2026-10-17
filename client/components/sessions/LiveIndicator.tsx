import { Text, View } from 'react-native';
import type { LiveMode } from '../../lib/realtime';

const COPY: Record<LiveMode, string> = {
  connecting: 'Connecting…',
  live: 'Live',
  polling: 'Updating every few seconds',
};

/** How the session screen is staying current: a green "Live" pill, or the polling / connecting fallback. */
export function LiveIndicator({ mode }: { mode: LiveMode }) {
  const live = mode === 'live';
  return (
    <View
      testID="live-indicator"
      accessible
      accessibilityLabel={live ? 'Live updates on' : COPY[mode]}
      className={`flex-row items-center gap-1.5 self-start rounded-full px-2.5 py-1 ${live ? 'bg-success-soft' : 'bg-raised'}`}
    >
      <View className={`h-2 w-2 rounded-full ${live ? 'bg-success' : mode === 'polling' ? 'bg-accent' : 'bg-muted'}`} />
      <Text className={`font-body-bold text-xs ${live ? 'text-success' : 'text-muted'}`}>{COPY[mode]}</Text>
    </View>
  );
}

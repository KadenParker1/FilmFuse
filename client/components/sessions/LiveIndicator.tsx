import { Text, View } from 'react-native';
import type { LiveMode } from '../../lib/realtime';

const COPY: Record<LiveMode, string> = {
  connecting: 'Connecting…',
  live: 'Live',
  polling: 'Updating every few seconds',
};

/** How the session screen is staying current: a pulsing-dot "Live" or the polling fallback. */
export function LiveIndicator({ mode }: { mode: LiveMode }) {
  const dot = mode === 'live' ? 'bg-success' : mode === 'polling' ? 'bg-gold' : 'bg-text-muted';
  return (
    <View
      testID="live-indicator"
      accessible
      accessibilityLabel={mode === 'live' ? 'Live updates on' : COPY[mode]}
      className="flex-row items-center gap-1.5 rounded-full border border-border bg-surface px-2.5 py-1"
    >
      <View className={`h-2 w-2 rounded-full ${dot}`} />
      <Text className="font-body-medium text-xs text-text-muted">{COPY[mode]}</Text>
    </View>
  );
}

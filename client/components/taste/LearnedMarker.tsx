import { View } from 'react-native';
import { formatScore } from './tasteCopy';

/** Where the vector's learned value sits on the 1–10 track (nothing when there's none yet). */
export function LearnedMarker({ learned, testID }: { learned: number | null; testID?: string }) {
  if (learned == null) return null;
  const clamped = Math.min(10, Math.max(1, learned));
  const left = `${((clamped - 1) / 9) * 100}%` as const;
  return (
    <View
      testID={testID}
      accessible
      accessibilityLabel={`Your ratings say ${formatScore(learned)} out of 10`}
      className="h-5 justify-center"
    >
      <View className="h-1 w-full rounded-full bg-border" />
      <View className="absolute -ml-2 h-4 w-4 rounded-full border-2 border-bg bg-velvet" style={{ left }} />
    </View>
  );
}

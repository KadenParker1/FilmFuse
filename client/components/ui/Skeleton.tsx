import { useEffect, useState } from 'react';
import { Animated, Platform, StyleSheet, View, type ViewStyle } from 'react-native';
import { useTheme } from '../../theme/useTheme';

type Props = { className?: string; style?: ViewStyle; testID?: string };

/** A softly pulsing placeholder block shown while content loads. Hidden from screen readers. */
export function Skeleton({ className = '', style, testID = 'skeleton' }: Props) {
  const { colors } = useTheme();
  const [opacity] = useState(() => new Animated.Value(0));

  useEffect(() => {
    const useNativeDriver = Platform.OS !== 'web';
    const loop = Animated.loop(
      Animated.sequence([
        Animated.timing(opacity, { toValue: 0.6, duration: 700, useNativeDriver }),
        Animated.timing(opacity, { toValue: 0, duration: 700, useNativeDriver }),
      ]),
    );
    loop.start();
    return () => loop.stop();
  }, [opacity]);

  return (
    <View
      testID={testID}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      className={`overflow-hidden rounded-[14px] bg-raised ${className}`}
      style={style}
    >
      <Animated.View style={[StyleSheet.absoluteFill, { opacity, backgroundColor: colors.border }]} />
    </View>
  );
}

import { Switch, type SwitchProps } from 'react-native';
import { useTheme } from '../../theme/useTheme';

/**
 * The app's only Switch: themed with tokens on every platform. Without explicit colours
 * react-native-web draws the "on" thumb teal (#009688) and Android uses Material teal too (bug 8).
 * Off uses the muted colour so the track keeps 3:1 against the surface.
 */
export function Toggle(props: Omit<SwitchProps, 'trackColor' | 'thumbColor' | 'ios_backgroundColor'>) {
  const { colors } = useTheme();
  const thumb = '#FFFFFF';
  return (
    <Switch
      trackColor={{ true: colors.accent, false: colors.muted }}
      thumbColor={thumb}
      ios_backgroundColor={colors.muted}
      // react-native-web only: the "on" thumb/track colours it otherwise defaults to teal.
      {...({ activeThumbColor: thumb, activeTrackColor: colors.accent } as object)}
      {...props}
    />
  );
}

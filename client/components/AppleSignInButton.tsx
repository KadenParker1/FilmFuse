import { Platform, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import * as AppleAuthentication from 'expo-apple-authentication';
import { useTheme } from '../theme/useTheme';
import { Button } from './ui';

type Props = { onPress: () => void; loading?: boolean; disabled?: boolean };

/** Matches our pill buttons (min-h-12, rounded-full). */
const HEIGHT = 48;

/**
 * "Continue with Apple". On iOS this is Apple's own ASAuthorizationAppleIDButton (App Store
 * guideline 4.8 / HIG): white on the dark scheme, black on the light one. Elsewhere (web) it's our
 * styled secondary button.
 */
export function AppleSignInButton({ onPress, loading = false, disabled = false }: Props) {
  const { scheme, colors } = useTheme();

  if (Platform.OS === 'ios') {
    const inactive = loading || disabled;
    return (
      <View
        testID="apple-native-button"
        accessibilityState={{ disabled: inactive, busy: loading }}
        pointerEvents={inactive ? 'none' : 'auto'}
        style={{ opacity: inactive ? 0.5 : 1 }}
      >
        <AppleAuthentication.AppleAuthenticationButton
          buttonType={AppleAuthentication.AppleAuthenticationButtonType.SIGN_IN}
          buttonStyle={
            scheme === 'dark'
              ? AppleAuthentication.AppleAuthenticationButtonStyle.WHITE
              : AppleAuthentication.AppleAuthenticationButtonStyle.BLACK
          }
          cornerRadius={HEIGHT / 2}
          style={{ width: '100%', height: HEIGHT }}
          onPress={onPress}
        />
      </View>
    );
  }

  return (
    <Button
      variant="secondary"
      title="Continue with Apple"
      loading={loading}
      disabled={disabled}
      onPress={onPress}
      icon={<Ionicons name="logo-apple" size={18} color={colors.text} />}
    />
  );
}

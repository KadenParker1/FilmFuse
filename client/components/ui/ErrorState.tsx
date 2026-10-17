import { View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { ApiError, NetworkError } from '../../lib/apiClient';
import { useTheme } from '../../theme/useTheme';
import { Button } from './Button';
import { Body, Heading } from './Text';

type Props = {
  title?: string;
  error?: unknown;
  /** Overrides the message derived from `error`. */
  message?: string;
  onRetry?: () => void;
  retrying?: boolean;
  compact?: boolean;
};

/** Friendly text for an error from apiClient. */
export function errorMessage(error: unknown): string {
  if (error instanceof NetworkError) return error.message;
  if (error instanceof ApiError) return error.status >= 500 ? 'The server had a problem. Try again in a moment.' : error.message;
  if (error instanceof Error && error.message) return error.message;
  return 'Something went wrong.';
}

/** Something failed to load: icon, message and an optional "Try again". */
export function ErrorState({ title = 'Something went wrong', error, message, onRetry, retrying, compact }: Props) {
  const { colors } = useTheme();
  return (
    <View
      className={`items-center justify-center gap-3 px-6 ${compact ? 'py-6' : 'flex-1 py-12'}`}
    >
      <View
        className={`items-center justify-center bg-danger-soft ${compact ? 'h-12 w-12 rounded-2xl' : 'h-20 w-20 rounded-3xl'}`}
      >
        <Ionicons name="cloud-offline-outline" size={compact ? 24 : 36} color={colors.danger} />
      </View>
      <Heading size="sm" className="text-center">
        {title}
      </Heading>
      <Body accessibilityRole="alert" muted className="max-w-sm text-center">
        {message ?? errorMessage(error)}
      </Body>
      {onRetry ? <Button title="Try again" variant="secondary" loading={retrying} onPress={onRetry} /> : null}
    </View>
  );
}

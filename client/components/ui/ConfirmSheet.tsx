import type { ReactNode } from 'react';
import { View } from 'react-native';
import { Button } from './Button';
import { errorMessage } from './ErrorState';
import { Sheet } from './Sheet';
import { Body } from './Text';

type Props = {
  visible: boolean;
  title: string;
  message?: string;
  /** Extra content between the message and the buttons (e.g. a poster). */
  children?: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  loading?: boolean;
  /** Shown as an alert above the buttons. */
  error?: unknown;
  onConfirm: () => void;
  onClose: () => void;
};

/** "Are you sure?" as a bottom sheet (Alert.alert does nothing on web). */
export function ConfirmSheet({
  visible,
  title,
  message,
  children,
  confirmLabel,
  danger = false,
  loading = false,
  error,
  onConfirm,
  onClose,
}: Props) {
  return (
    <Sheet visible={visible} onClose={onClose} title={title}>
      <View className="gap-4">
        {message ? <Body muted>{message}</Body> : null}
        {children}
        {error ? (
          <Body accessibilityRole="alert" className="text-sm text-velvet">
            {errorMessage(error)}
          </Body>
        ) : null}
        <Button title={confirmLabel} variant={danger ? 'danger' : 'primary'} loading={loading} onPress={onConfirm} />
        <Button title="Cancel" variant="ghost" disabled={loading} onPress={onClose} />
      </View>
    </Sheet>
  );
}

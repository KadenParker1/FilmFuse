import type { ReactNode } from 'react';
import { Modal, Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ThemeRoot } from '../ThemeRoot';
import { Heading } from './Text';

type Props = {
  visible: boolean;
  onClose: () => void;
  title?: string;
  children: ReactNode;
};

/** A simple bottom sheet: a slide-up Modal with a tappable backdrop. */
export function Sheet({ visible, onClose, title, children }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <ThemeRoot className="flex-1 justify-end">
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Close"
          onPress={onClose}
          className="absolute inset-0 bg-black/60"
        />
        <View
          accessibilityViewIsModal
          className="w-full max-w-xl self-center rounded-t-3xl border border-b-0 border-border bg-surface px-5 pt-3"
          style={{ paddingBottom: Math.max(insets.bottom, 16) + 8 }}
        >
          <View className="mb-4 h-1.5 w-12 self-center rounded-full bg-border" />
          {title ? (
            <Heading size="md" className="mb-4">
              {title}
            </Heading>
          ) : null}
          {children}
        </View>
      </ThemeRoot>
    </Modal>
  );
}

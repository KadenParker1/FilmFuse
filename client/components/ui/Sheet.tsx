import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Modal, Platform, Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ThemeRoot } from '../ThemeRoot';
import { Heading } from './Text';

type Props = {
  visible: boolean;
  onClose: () => void;
  title?: string;
  children: ReactNode;
  /** iOS: the Modal finished animating out (see useSheetHandoff). */
  onDismiss?: () => void;
};

/**
 * On web the Modal fades instead of sliding: react-native-web slides the whole full-screen layer
 * (backdrop included) up from off-screen, so for the first ~300 ms the page underneath is exposed
 * and takes clicks meant for the sheet - one cause of taps landing on the Discover grid (bug 5).
 */
export const sheetAnimation = (os: string = Platform.OS): 'fade' | 'slide' => (os === 'web' ? 'fade' : 'slide');

/**
 * iOS doesn't resize a Modal for the keyboard, so a sheet with a text field (report details, a
 * rating note, a group name, the delete-account confirmation) would type behind it: pad the sheet
 * up instead. Android resizes the Modal's window itself (adjustResize); web has no soft keyboard.
 */
export const sheetKeyboardBehavior = (os: string = Platform.OS): 'padding' | undefined =>
  os === 'ios' ? 'padding' : undefined;

/**
 * A simple bottom sheet: a slide-up Modal (fade on web) with a tappable backdrop. The Android back
 * button / gesture closes it (`onRequestClose`), like the backdrop.
 */
export function Sheet({ visible, onClose, title, children, onDismiss }: Props) {
  const insets = useSafeAreaInsets();
  return (
    <Modal
      visible={visible}
      transparent
      animationType={sheetAnimation()}
      onRequestClose={onClose}
      onDismiss={onDismiss}
    >
      <ThemeRoot className="flex-1">
        <KeyboardAvoidingView className="flex-1 justify-end" behavior={sheetKeyboardBehavior()}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Close"
            onPress={onClose}
            className="absolute inset-0 bg-black/60"
          />
          <View
            accessibilityViewIsModal
            className="w-full max-w-xl self-center rounded-t-[28px] bg-surface px-5 pt-3"
            style={{ paddingBottom: Math.max(insets.bottom, 16) + 8 }}
          >
            <View className="mb-4 h-1.5 w-10 self-center rounded-full bg-raised" />
            {title ? (
              <Heading size="md" className="mb-4">
                {title}
              </Heading>
            ) : null}
            {children}
          </View>
        </KeyboardAvoidingView>
      </ThemeRoot>
    </Modal>
  );
}

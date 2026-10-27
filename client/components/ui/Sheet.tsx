import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Modal, Platform, Pressable, ScrollView, View, type ViewStyle } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { ThemeRoot } from '../ThemeRoot';
import { Heading } from './Text';
import { useWebKeyboardViewport, type ViewportGap } from './useWebKeyboardViewport';

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
 * up instead. Android resizes the Modal's window itself (adjustResize); on web, webSheetLayout
 * follows the visual viewport instead.
 */
export const sheetKeyboardBehavior = (os: string = Platform.OS): 'padding' | undefined =>
  os === 'ios' ? 'padding' : undefined;

/**
 * Web (iPhone Safari): keep the sheet inside what is actually visible - below the status bar /
 * notch, and above the on-screen keyboard (see useWebKeyboardViewport) - and let a sheet taller
 * than that scroll instead of being cut off. Native: null - KeyboardAvoidingView and the Modal
 * window already handle it there, and nothing changes.
 */
export function webSheetLayout(
  os: string,
  insetTop: number,
  gap: ViewportGap,
): { frame: ViewStyle; panel: ViewStyle } | null {
  if (os !== 'web') return null;
  return {
    frame: { paddingTop: gap.top + insetTop, paddingBottom: gap.bottom },
    panel: { maxHeight: '100%' },
  };
}

/** Space under the sheet's content: clear of the home indicator - unless the keyboard covers it. */
export function sheetBottomPadding(insetBottom: number, keyboardGap: number): number {
  return (keyboardGap > 0 ? 16 : Math.max(insetBottom, 16)) + 8;
}

/**
 * A simple bottom sheet: a slide-up Modal (fade on web) with a tappable backdrop. The Android back
 * button / gesture closes it (`onRequestClose`), like the backdrop.
 */
export function Sheet({ visible, onClose, title, children, onDismiss }: Props) {
  const insets = useSafeAreaInsets();
  const gap = useWebKeyboardViewport(visible);
  const web = webSheetLayout(Platform.OS, insets.top, gap);
  return (
    <Modal
      visible={visible}
      transparent
      animationType={sheetAnimation()}
      onRequestClose={onClose}
      onDismiss={onDismiss}
    >
      <ThemeRoot className="flex-1">
        <KeyboardAvoidingView className="flex-1 justify-end" behavior={sheetKeyboardBehavior()} style={web?.frame}>
          <Pressable
            accessibilityRole="button"
            accessibilityLabel="Close"
            onPress={onClose}
            className="absolute inset-0 bg-black/60"
          />
          <View
            accessibilityViewIsModal
            className="w-full max-w-xl self-center rounded-t-[28px] bg-surface px-5 pt-3"
            style={[{ paddingBottom: sheetBottomPadding(insets.bottom, gap.bottom) }, web?.panel]}
          >
            <View className="mb-4 h-1.5 w-10 self-center rounded-full bg-raised" />
            {title ? (
              <Heading size="md" className="mb-4">
                {title}
              </Heading>
            ) : null}
            {web ? (
              // flexGrow 0: as tall as its content; shrinks and scrolls only when it doesn't fit.
              <ScrollView testID="sheet-scroll" style={{ flexGrow: 0 }} keyboardShouldPersistTaps="handled">
                {children}
              </ScrollView>
            ) : (
              children
            )}
          </View>
        </KeyboardAvoidingView>
      </ThemeRoot>
    </Modal>
  );
}

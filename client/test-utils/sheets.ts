import { act } from '@testing-library/react-native';
import { SHEET_DISMISS_FALLBACK_MS } from '../components/ui/useSheetHandoff';

/**
 * Jest runs as iOS, where an action handed off from a closing sheet (useSheetHandoff) waits for the
 * Modal's onDismiss. A hidden Modal isn't rendered under test, so let the fallback timer fire.
 * Needs fake timers (`jest.useFakeTimers()`).
 */
export function finishSheetDismiss() {
  return act(async () => {
    jest.advanceTimersByTime(SHEET_DISMISS_FALLBACK_MS);
  });
}

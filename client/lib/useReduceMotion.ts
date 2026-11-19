import { useEffect, useState } from 'react';
import { AccessibilityInfo } from 'react-native';

/**
 * Whether the OS asks for reduced motion (iOS/Android setting; `prefers-reduced-motion` on web via
 * react-native-web). Starts false, then follows the setting and its changes.
 */
export function useReduceMotion(): boolean {
  const [reduce, setReduce] = useState(false);
  useEffect(() => {
    let active = true;
    AccessibilityInfo.isReduceMotionEnabled()
      .then((value) => {
        if (active) setReduce(value);
      })
      .catch(() => {});
    const sub = AccessibilityInfo.addEventListener('reduceMotionChanged', (value) => setReduce(value));
    return () => {
      active = false;
      sub?.remove();
    };
  }, []);
  return reduce;
}

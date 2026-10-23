import { Text } from 'react-native';
import { useCountdown } from './useCountdown';

/**
 * Small, quiet "Closes in 3h 05m" for an open session's header. Deliberately not a live region:
 * screen readers get "Voting closes in 3 hours 5 minutes" when they focus it, never a tick-by-tick
 * announcement. `onExpire` fires at zero so the screen can refetch and show the session closed.
 */
export function SessionCountdown({
  closesAt,
  onExpire,
  testID = 'session-countdown',
}: {
  closesAt: string;
  onExpire?: () => void;
  testID?: string;
}) {
  const { label, spoken } = useCountdown(closesAt, onExpire);
  return (
    <Text
      testID={testID}
      accessibilityLabel={spoken}
      accessibilityLiveRegion="none"
      aria-live="off"
      className="font-body text-xs text-muted"
    >
      {label}
    </Text>
  );
}

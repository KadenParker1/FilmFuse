import type { OnboardingStateDTO } from '../../types/api';
import { CalibrationDeck } from '../taste/CalibrationDeck';
import { OnboardingFrame } from './OnboardingFrame';
import { CALIBRATION_MIN_RATED, CALIBRATION_SKIP_AFTER, describeError } from './steps';
import { useAdvance } from './useAdvance';

/** Step 4: the calibration deck. "Done" after 10 rated; "Skip for now" after 20 cards seen. */
export function CalibrationStep({ state }: { state: OnboardingStateDTO }) {
  const nav = useAdvance(4);
  return (
    <OnboardingFrame
      step={4}
      title="Rate what you’ve seen"
      subtitle="Score the ones you’ve watched. Tap “Haven’t seen” to skip the rest."
      onBack={nav.back}
      error={describeError(nav.error)}
    >
      <CalibrationDeck
        deckId="onboarding"
        minRated={CALIBRATION_MIN_RATED}
        skipAfter={CALIBRATION_SKIP_AFTER}
        initialRatedCount={state.calibrationRatedCount}
        onFinish={nav.advance}
      />
    </OnboardingFrame>
  );
}

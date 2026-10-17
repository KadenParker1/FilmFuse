import { Redirect, useLocalSearchParams } from 'expo-router';
import { useMe } from '../../../api/me';
import { useOnboardingState } from '../../../api/onboarding';
import { FullScreenLoader } from '../../../components/FullScreenLoader';
import { CalibrationStep } from '../../../components/onboarding/CalibrationStep';
import { ComfortStep } from '../../../components/onboarding/ComfortStep';
import { FavoritesStep } from '../../../components/onboarding/FavoritesStep';
import { FriendsStep } from '../../../components/onboarding/FriendsStep';
import { GenresStep } from '../../../components/onboarding/GenresStep';
import { RevealStep } from '../../../components/onboarding/RevealStep';
import { clampStep, onboardingPath, TOTAL_STEPS } from '../../../components/onboarding/steps';
import { VibesStep } from '../../../components/onboarding/VibesStep';
import { YouStep } from '../../../components/onboarding/YouStep';
import { ErrorState, Screen } from '../../../components/ui';

/**
 * One onboarding step (`/onboarding/1` … `/onboarding/8`). Steps up to the saved one can be revisited;
 * a later step (or a bad number) redirects to where the user left off. Finished users go to the app,
 * except on the reveal step, which is what finishes onboarding.
 */
export default function OnboardingStepScreen() {
  const params = useLocalSearchParams<{ step: string }>();
  const requested = Number(Array.isArray(params.step) ? params.step[0] : params.step);
  const me = useMe();
  const state = useOnboardingState();

  if (me.isPending || state.isPending) return <FullScreenLoader label="Loading your progress" />;
  const failed = me.isError ? me : state.isError ? state : null;
  if (failed) {
    return (
      <Screen>
        <ErrorState
          title="Couldn't load your progress"
          error={failed.error}
          onRetry={() => {
            void me.refetch();
            void state.refetch();
          }}
          retrying={me.isFetching || state.isFetching}
        />
      </Screen>
    );
  }
  const profile = me.data!;
  const onboarding = state.data!;

  if (profile.onboardingCompletedAt && requested !== TOTAL_STEPS) return <Redirect href="/" />;
  const resumeAt = clampStep(onboarding.step);
  const valid = Number.isInteger(requested) && requested >= 1 && requested <= TOTAL_STEPS;
  if (!valid || (requested > resumeAt && !profile.onboardingCompletedAt)) {
    return <Redirect href={onboardingPath(resumeAt)} />;
  }

  // Keyed by step so each one starts from the saved answers.
  switch (requested) {
    case 1:
      return <YouStep key={1} profile={profile} />;
    case 2:
      return <GenresStep key={2} state={onboarding} />;
    case 3:
      return <FavoritesStep key={3} state={onboarding} />;
    case 4:
      return <CalibrationStep key={4} state={onboarding} />;
    case 5:
      return <VibesStep key={5} state={onboarding} />;
    case 6:
      return <ComfortStep key={6} state={onboarding} />;
    case 7:
      return <FriendsStep key={7} />;
    default:
      return <RevealStep key={8} />;
  }
}

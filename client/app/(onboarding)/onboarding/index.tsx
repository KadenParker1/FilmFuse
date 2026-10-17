import { Redirect } from 'expo-router';
import { useOnboardingState } from '../../../api/onboarding';
import { FullScreenLoader } from '../../../components/FullScreenLoader';
import { onboardingPath } from '../../../components/onboarding/steps';
import { ErrorState, Screen } from '../../../components/ui';

/** `/onboarding` → the step the user saved last (resumable), or the app once they're done. */
export default function OnboardingIndex() {
  const state = useOnboardingState();
  if (state.isPending) return <FullScreenLoader label="Loading your progress" />;
  if (state.isError) {
    return (
      <Screen>
        <ErrorState
          title="Couldn't load your progress"
          error={state.error}
          onRetry={() => void state.refetch()}
          retrying={state.isFetching}
        />
      </Screen>
    );
  }
  if (state.data.completed) return <Redirect href="/" />;
  return <Redirect href={onboardingPath(state.data.step)} />;
}

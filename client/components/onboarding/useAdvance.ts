import { useRouter } from 'expo-router';
import { useSetOnboardingStep } from '../../api/onboarding';
import { onboardingPath } from './steps';

/**
 * Moving between steps. `advance` records the next step server-side first (so a reload resumes
 * there) and only then navigates; it resolves false when that save failed (see `error`).
 */
export function useAdvance(step: number) {
  const router = useRouter();
  const setStep = useSetOnboardingStep();
  return {
    advance: async (): Promise<boolean> => {
      try {
        await setStep.mutateAsync(step + 1);
      } catch {
        return false;
      }
      router.replace(onboardingPath(step + 1));
      return true;
    },
    back: () => router.replace(onboardingPath(step - 1)),
    isPending: setStep.isPending,
    error: setStep.error,
    reset: setStep.reset,
  };
}

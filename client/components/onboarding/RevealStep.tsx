import { useEffect, useRef } from 'react';
import { ActivityIndicator, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useCompleteOnboarding } from '../../api/me';
import { useTasteProfile } from '../../api/taste';
import { useTheme } from '../../theme/useTheme';
import { PersonaCard } from '../taste/PersonaCard';
import { Body, Button, ErrorState } from '../ui';
import { OnboardingFrame } from './OnboardingFrame';
import { describeError } from './steps';
import { useAdvance } from './useAdvance';

/**
 * Step 8: completes onboarding (POST /me/onboarding/complete, which checks the minimums and starts
 * the first taste build), then shows the persona card, polling until the profile is FRESH.
 */
export function RevealStep() {
  const router = useRouter();
  const { colors } = useTheme();
  const nav = useAdvance(8);
  const complete = useCompleteOnboarding();
  const taste = useTasteProfile({ enabled: complete.isSuccess, pollWhileNone: true });
  const started = useRef(false);
  const { mutate } = complete;

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    mutate();
  }, [mutate]);

  let content;
  if (complete.isError) {
    content = (
      <View testID="reveal-error" className="gap-3">
        <Body accessibilityRole="alert" className="text-center text-danger">
          {describeError(complete.error)}
        </Body>
        <Button title="Try again" variant="secondary" onPress={() => complete.mutate()} />
      </View>
    );
  } else if (!complete.isSuccess) {
    content = (
      <View testID="reveal-completing" className="items-center gap-3 py-10">
        <ActivityIndicator color={colors.accent} />
        <Body muted>Putting your taste together…</Body>
      </View>
    );
  } else if (taste.isError) {
    content = (
      <ErrorState
        compact
        title="Couldn't load your taste"
        error={taste.error}
        onRetry={() => void taste.refetch()}
        retrying={taste.isFetching}
      />
    );
  } else {
    const fresh = taste.data?.status === 'FRESH';
    content = (
      <View className="gap-4">
        {taste.data ? <PersonaCard profile={taste.data} /> : null}
        {!fresh ? (
          <View testID="reveal-building" className="flex-row items-center justify-center gap-2">
            <ActivityIndicator size="small" color={colors.accent} />
            <Body muted>Still tuning your taste profile…</Body>
          </View>
        ) : null}
      </View>
    );
  }

  return (
    <OnboardingFrame
      step={8}
      title="Your taste"
      subtitle="Here’s what we learned. It gets sharper every time you rate something."
      onBack={complete.isSuccess ? undefined : nav.back}
      onContinue={() => router.replace('/')}
      continueLabel="Start exploring"
      continueDisabled={!complete.isSuccess}
    >
      {content}
    </OnboardingFrame>
  );
}

import type { ReactNode } from 'react';
import { KeyboardAvoidingView, Platform, Text, View } from 'react-native';
import { Body, Button, Heading, Screen } from '../ui';
import { ONBOARDING_STEPS, TOTAL_STEPS } from './steps';

type Props = {
  step: number;
  title: string;
  subtitle?: string;
  children: ReactNode;
  /** Omitted on the first step. */
  onBack?: () => void;
  /** Omitted when the step drives itself (calibration's Done, the reveal's Start exploring). */
  onContinue?: () => void;
  continueLabel?: string;
  continueDisabled?: boolean;
  loading?: boolean;
  /** Shown as an alert above the buttons (server validation errors included). */
  error?: string | null;
  /** A live line above the buttons (e.g. what's still needed to continue). */
  hint?: string | null;
  /** Steps with their own list (friend search) don't scroll the whole page. */
  scroll?: boolean;
  footer?: ReactNode;
};

/** One onboarding step: progress bar, title, the step's content, then Back / Continue. */
export function OnboardingFrame({
  step,
  title,
  subtitle,
  children,
  onBack,
  onContinue,
  continueLabel = 'Continue',
  continueDisabled = false,
  loading = false,
  error,
  hint,
  scroll = true,
  footer,
}: Props) {
  const pct = `${(step / TOTAL_STEPS) * 100}%` as const;
  return (
    <KeyboardAvoidingView className="flex-1" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll={scroll}>
        <View className="w-full max-w-xl flex-1 gap-6 self-center">
          <View className="gap-2">
            <View className="flex-row justify-between">
              <Text testID="onboarding-step" className="font-body-semibold text-sm text-text-muted">
                Step {step} of {TOTAL_STEPS}
              </Text>
              <Text className="font-body text-sm text-text-muted">{ONBOARDING_STEPS[step - 1]}</Text>
            </View>
            <View
              testID="onboarding-progress"
              accessibilityRole="progressbar"
              accessibilityLabel="Onboarding progress"
              accessibilityValue={{ min: 0, max: TOTAL_STEPS, now: step, text: `Step ${step} of ${TOTAL_STEPS}` }}
              className="h-1.5 w-full overflow-hidden rounded-full bg-border"
            >
              <View className="h-1.5 rounded-full bg-gold" style={{ width: pct }} />
            </View>
          </View>
          <View className="gap-2">
            <Heading size="lg">{title}</Heading>
            {subtitle ? <Body muted>{subtitle}</Body> : null}
          </View>
          <View className={scroll ? 'gap-4' : 'flex-1 gap-4'}>{children}</View>
          <View className="gap-3 pb-6">
            {hint ? (
              <Body testID="onboarding-hint" accessibilityLiveRegion="polite" muted className="text-center text-sm">
                {hint}
              </Body>
            ) : null}
            {error ? (
              <Body testID="onboarding-error" accessibilityRole="alert" className="text-center text-sm text-velvet">
                {error}
              </Body>
            ) : null}
            <View className="flex-row gap-3">
              {onBack ? (
                <Button title="Back" variant="secondary" className="flex-1" disabled={loading} onPress={onBack} />
              ) : null}
              {onContinue ? (
                <Button
                  title={continueLabel}
                  className="flex-1"
                  loading={loading}
                  disabled={continueDisabled}
                  onPress={onContinue}
                />
              ) : null}
            </View>
            {footer}
          </View>
        </View>
      </Screen>
    </KeyboardAvoidingView>
  );
}

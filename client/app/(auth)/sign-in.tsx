import { useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { AppleSignInButton } from '../../components/AppleSignInButton';
import { Body, Button, Card, Heading, Screen, TextField } from '../../components/ui';
import { useAuth } from '../../auth/useAuth';
import { showAppleButton, signInWithProvider } from '../../auth/oauth';
import type { OAuthProvider } from '../../auth/authRedirect';
import { MIN_PASSWORD, validateAuthForm, type AuthFieldErrors, type AuthMode as Mode } from '../../auth/validateAuthForm';
import { env } from '../../lib/env';
import { useTheme } from '../../theme/useTheme';

const COPY: Record<Mode, { heading: string; tagline: string; submit: string }> = {
  'sign-in': {
    heading: 'Welcome back',
    tagline: 'Rate what you watch. Pick movies everyone in the room will love.',
    submit: 'Sign in',
  },
  'sign-up': {
    heading: 'Grab a seat',
    tagline: 'Create your account — your taste profile takes about three minutes.',
    submit: 'Create account',
  },
  forgot: {
    heading: 'Reset your password',
    tagline: "Enter your email and we'll send you a link to get back in.",
    submit: 'Send reset link',
  },
};

export default function SignInScreen() {
  const { signInWithPassword, signUp, resetPassword } = useAuth();
  const { colors } = useTheme();
  const [mode, setMode] = useState<Mode>('sign-in');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [providerBusy, setProviderBusy] = useState<OAuthProvider | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<AuthFieldErrors>({});

  function switchMode(next: Mode) {
    setMode(next);
    setError(null);
    setNotice(null);
    setFieldErrors({});
    setConfirm('');
  }

  async function submit() {
    setError(null);
    setNotice(null);
    const errs = validateAuthForm(mode, email, password, confirm);
    setFieldErrors(errs);
    if (Object.keys(errs).length > 0) return;

    setSubmitting(true);
    try {
      if (mode === 'sign-in') {
        const { error: err } = await signInWithPassword(email.trim(), password);
        if (err) setError(err.message);
      } else if (mode === 'sign-up') {
        const { data, error: err } = await signUp(email.trim(), password);
        if (err) setError(err.message);
        else if (!data.session) setNotice('Check your inbox to confirm your email, then sign in.');
      } else {
        const { error: err } = await resetPassword(email.trim());
        if (err) setError(err.message);
        else setNotice('If that email has an account, a reset link is on its way.');
      }
    } catch {
      setError("Couldn't reach the sign-in service. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  async function withProvider(provider: OAuthProvider) {
    setError(null);
    setNotice(null);
    setProviderBusy(provider);
    try {
      const result = await signInWithProvider(provider);
      if (!result.ok && !result.cancelled) setError(result.error ?? 'Sign-in failed. Try again.');
    } finally {
      setProviderBusy(null);
    }
  }

  const copy = COPY[mode];
  const isForgot = mode === 'forgot';
  const isSignUp = mode === 'sign-up';
  const busy = submitting || providerBusy !== null;

  return (
    <KeyboardAvoidingView className="flex-1" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll className="justify-center">
        <View className="w-full max-w-md gap-6 self-center">
          <View className="gap-2">
            <View className="flex-row items-center gap-2.5">
              <View className="h-8 w-8 items-center justify-center rounded-[10px] bg-accent">
                <Ionicons name="play" size={16} color={colors.onAccent} />
              </View>
              <Text className="font-display-bold text-lg tracking-tight text-text">FilmFuse</Text>
            </View>
            <Heading size="xl">{copy.heading}</Heading>
            <Body muted>{copy.tagline}</Body>
          </View>

          {!env.isSupabaseConfigured ? (
            <Card raised>
              <Body className="text-danger">
                Supabase isn&apos;t configured. Copy .env.example to .env.local and set EXPO_PUBLIC_SUPABASE_URL and
                EXPO_PUBLIC_SUPABASE_ANON_KEY.
              </Body>
            </Card>
          ) : null}

          <Card className="gap-4">
            <TextField
              label="Email"
              value={email}
              onChangeText={setEmail}
              autoCapitalize="none"
              autoComplete="email"
              keyboardType="email-address"
              textContentType="emailAddress"
              placeholder="you@example.com"
              error={fieldErrors.email}
              onSubmitEditing={isForgot ? submit : undefined}
            />
            {!isForgot ? (
              <TextField
                label="Password"
                value={password}
                onChangeText={setPassword}
                secureTextEntry
                autoComplete={isSignUp ? 'new-password' : 'current-password'}
                textContentType={isSignUp ? 'newPassword' : 'password'}
                hint={isSignUp ? `At least ${MIN_PASSWORD} characters` : undefined}
                error={fieldErrors.password}
                onSubmitEditing={isSignUp ? undefined : submit}
              />
            ) : null}
            {isSignUp ? (
              <TextField
                label="Confirm password"
                value={confirm}
                onChangeText={setConfirm}
                secureTextEntry
                autoComplete="new-password"
                textContentType="newPassword"
                error={fieldErrors.confirm}
                onSubmitEditing={submit}
              />
            ) : null}
            {mode === 'sign-in' ? (
              <Pressable
                accessibilityRole="button"
                onPress={() => switchMode('forgot')}
                className="-mt-1 self-end p-1 active:opacity-60"
              >
                <Text className="font-body-medium text-sm text-accent-ink">Forgot password?</Text>
              </Pressable>
            ) : null}
            {error ? (
              <Body testID="auth-error" accessibilityLiveRegion="polite" className="text-danger">
                {error}
              </Body>
            ) : null}
            {notice ? (
              <Body testID="auth-notice" accessibilityLiveRegion="polite" className="text-success">
                {notice}
              </Body>
            ) : null}
            <Button title={copy.submit} loading={submitting} disabled={busy && !submitting} onPress={submit} />
          </Card>

          {!isForgot ? (
            <View className="gap-3">
              <View className="flex-row items-center gap-3">
                <View className="h-px flex-1 bg-border" />
                <Body muted className="text-sm">
                  or
                </Body>
                <View className="h-px flex-1 bg-border" />
              </View>
              <Button
                variant="secondary"
                title="Continue with Google"
                loading={providerBusy === 'google'}
                disabled={busy && providerBusy !== 'google'}
                onPress={() => void withProvider('google')}
                icon={<Ionicons name="logo-google" size={18} color={colors.text} />}
              />
              {showAppleButton ? (
                <AppleSignInButton
                  loading={providerBusy === 'apple'}
                  disabled={busy && providerBusy !== 'apple'}
                  onPress={() => void withProvider('apple')}
                />
              ) : null}
            </View>
          ) : null}

          <Pressable
            accessibilityRole="button"
            onPress={() => switchMode(mode === 'sign-in' ? 'sign-up' : 'sign-in')}
            className="self-center p-2"
          >
            <Body muted>
              {mode === 'sign-in' ? 'New here? ' : isForgot ? 'Remembered it? ' : 'Already have an account? '}
              <Text className="font-body-semibold text-accent-ink">{mode === 'sign-in' ? 'Create an account' : 'Sign in'}</Text>
            </Body>
          </Pressable>
        </View>
      </Screen>
    </KeyboardAvoidingView>
  );
}

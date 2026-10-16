import { useState } from 'react';
import { KeyboardAvoidingView, Platform, Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Body, Button, Card, Heading, Screen, TextField } from '../../components/ui';
import { useAuth } from '../../auth/useAuth';
import { env } from '../../lib/env';
import { useTheme } from '../../theme/useTheme';

type Mode = 'sign-in' | 'sign-up';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export default function SignInScreen() {
  const { signInWithPassword, signUp } = useAuth();
  const { colors } = useTheme();
  const [mode, setMode] = useState<Mode>('sign-in');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<{ email?: string; password?: string }>({});

  async function submit() {
    setError(null);
    setNotice(null);
    const errs: typeof fieldErrors = {};
    if (!EMAIL_RE.test(email.trim())) errs.email = 'Enter a valid email address';
    if (password.length < (mode === 'sign-up' ? 8 : 1))
      errs.password = mode === 'sign-up' ? 'Use at least 8 characters' : 'Enter your password';
    setFieldErrors(errs);
    if (Object.keys(errs).length > 0) return;

    setSubmitting(true);
    try {
      if (mode === 'sign-in') {
        const { error: err } = await signInWithPassword(email.trim(), password);
        if (err) setError(err.message);
      } else {
        const { data, error: err } = await signUp(email.trim(), password);
        if (err) setError(err.message);
        else if (!data.session) setNotice('Check your inbox to confirm your email, then sign in.');
      }
    } catch {
      setError("Couldn't reach the sign-in service. Try again.");
    } finally {
      setSubmitting(false);
    }
  }

  const isSignIn = mode === 'sign-in';

  return (
    <KeyboardAvoidingView className="flex-1" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll className="justify-center">
        <View className="w-full max-w-md gap-6 self-center">
          <View className="gap-2">
            <Text className="font-body-semibold text-sm uppercase tracking-[3px] text-gold">FilmFuse</Text>
            <Heading size="xl">{isSignIn ? 'Welcome back' : 'Grab a seat'}</Heading>
            <Body muted>Rate what you watch. Pick movies everyone in the room will love.</Body>
          </View>

          {!env.isSupabaseConfigured ? (
            <Card raised>
              <Body className="text-velvet">
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
            />
            <TextField
              label="Password"
              value={password}
              onChangeText={setPassword}
              secureTextEntry
              autoComplete={isSignIn ? 'current-password' : 'new-password'}
              textContentType={isSignIn ? 'password' : 'newPassword'}
              error={fieldErrors.password}
              onSubmitEditing={submit}
            />
            {error ? (
              <Body accessibilityLiveRegion="polite" className="text-velvet">
                {error}
              </Body>
            ) : null}
            {notice ? (
              <Body accessibilityLiveRegion="polite" className="text-success">
                {notice}
              </Body>
            ) : null}
            <Button title={isSignIn ? 'Sign in' : 'Create account'} loading={submitting} onPress={submit} />
          </Card>

          <View className="gap-3">
            <Button
              variant="secondary"
              title="Continue with Google"
              disabled
              icon={<Ionicons name="logo-google" size={18} color={colors.text} />}
            />
            <Button
              variant="secondary"
              title="Continue with Apple"
              disabled
              icon={<Ionicons name="logo-apple" size={18} color={colors.text} />}
            />
            <Body muted className="text-center text-sm">
              Google and Apple sign-in are coming in Phase 1a.
            </Body>
          </View>

          <Pressable
            accessibilityRole="button"
            onPress={() => {
              setMode(isSignIn ? 'sign-up' : 'sign-in');
              setError(null);
              setNotice(null);
              setFieldErrors({});
            }}
            className="self-center p-2"
          >
            <Body muted>
              {isSignIn ? 'New here? ' : 'Already have an account? '}
              <Text className="font-body-semibold text-gold">{isSignIn ? 'Create an account' : 'Sign in'}</Text>
            </Body>
          </Pressable>
        </View>
      </Screen>
    </KeyboardAvoidingView>
  );
}

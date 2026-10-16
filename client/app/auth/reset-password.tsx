import { useState } from 'react';
import { KeyboardAvoidingView, Platform, View } from 'react-native';
import { useRouter } from 'expo-router';
import { useAuth } from '../../auth/useAuth';
import { MIN_PASSWORD, validateNewPassword, type AuthFieldErrors } from '../../auth/validateAuthForm';
import { Body, Button, Card, EmptyState, Heading, Screen, TextField } from '../../components/ui';

/** Reached from a password-reset email (the recovery link has already signed the user in). */
export default function ResetPasswordScreen() {
  const router = useRouter();
  const { session, updatePassword, clearPasswordRecovery, signOut } = useAuth();
  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [fieldErrors, setFieldErrors] = useState<AuthFieldErrors>({});
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [done, setDone] = useState(false);

  async function submit() {
    setError(null);
    const errs = validateNewPassword(password, confirm);
    setFieldErrors(errs);
    if (Object.keys(errs).length > 0) return;
    setSaving(true);
    try {
      const { error: err } = await updatePassword(password);
      if (err) setError(err.message);
      else setDone(true);
    } catch {
      setError("Couldn't reach the sign-in service. Try again.");
    } finally {
      setSaving(false);
    }
  }

  function continueToApp() {
    clearPasswordRecovery();
    router.replace('/');
  }

  if (!session) {
    return (
      <Screen>
        <EmptyState
          icon="key-outline"
          title="This reset link has expired"
          message="Request a new link from the sign-in screen."
        />
        <View className="w-full max-w-sm self-center pb-6">
          <Button title="Back to sign in" onPress={() => router.replace('/sign-in')} />
        </View>
      </Screen>
    );
  }

  return (
    <KeyboardAvoidingView className="flex-1" behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
      <Screen scroll className="justify-center">
        <View className="w-full max-w-md gap-6 self-center">
          <View className="gap-2">
            <Heading size="xl">{done ? 'Password updated' : 'Choose a new password'}</Heading>
            <Body muted>
              {done ? "You're signed in with your new password." : `Use at least ${MIN_PASSWORD} characters.`}
            </Body>
          </View>
          {done ? (
            <Button title="Continue to FilmFuse" onPress={continueToApp} />
          ) : (
            <Card className="gap-4">
              <TextField
                label="New password"
                value={password}
                onChangeText={setPassword}
                secureTextEntry
                autoComplete="new-password"
                textContentType="newPassword"
                error={fieldErrors.password}
              />
              <TextField
                label="Confirm new password"
                value={confirm}
                onChangeText={setConfirm}
                secureTextEntry
                autoComplete="new-password"
                textContentType="newPassword"
                error={fieldErrors.confirm}
                onSubmitEditing={submit}
              />
              {error ? (
                <Body testID="reset-error" accessibilityLiveRegion="polite" className="text-velvet">
                  {error}
                </Body>
              ) : null}
              <Button title="Update password" loading={saving} onPress={submit} />
              <Button
                title="Cancel and sign out"
                variant="ghost"
                disabled={saving}
                onPress={() => {
                  clearPasswordRecovery();
                  void signOut();
                }}
              />
            </Card>
          )}
        </View>
      </Screen>
    </KeyboardAvoidingView>
  );
}

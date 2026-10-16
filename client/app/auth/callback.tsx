import { useEffect, useRef, useState } from 'react';
import { View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { RESET_PASSWORD_ROUTE } from '../../auth/authRedirect';
import { completeAuthCallback } from '../../auth/completeAuthCallback';
import { FullScreenLoader } from '../../components/FullScreenLoader';
import { Button, ErrorState, Screen } from '../../components/ui';

const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

/**
 * OAuth / email-confirmation / password-reset redirects land here with `?code=` (PKCE). Reset links
 * carry `type=recovery` and continue to the new-password screen.
 */
export default function AuthCallbackScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ code?: string; error?: string; error_description?: string; type?: string }>();
  const code = first(params.code);
  const error = first(params.error_description) ?? first(params.error);
  const recovery = first(params.type) === 'recovery';
  const [failure, setFailure] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    // A code can only be exchanged once; guard against effect re-runs.
    if (started.current) return;
    started.current = true;
    void completeAuthCallback({ code, error }).then((outcome) => {
      if (outcome.ok) router.replace(recovery ? RESET_PASSWORD_ROUTE : '/');
      else setFailure(outcome.error);
    });
  }, [code, error, recovery, router]);

  if (!failure) return <FullScreenLoader label="Signing you in" />;

  return (
    <Screen>
      <ErrorState title="Couldn't sign you in" message={failure} />
      <View className="w-full max-w-sm self-center pb-6">
        <Button title="Back to sign in" onPress={() => router.replace('/sign-in')} />
      </View>
    </Screen>
  );
}

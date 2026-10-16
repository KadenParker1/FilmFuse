import { useState, type ReactNode } from 'react';
import { View } from 'react-native';
import { Redirect } from 'expo-router';
import { useMe } from '../api/me';
import { useAuth } from '../auth/useAuth';
import { ApiError } from '../lib/apiClient';
import { Body, Button, EmptyState, Screen } from './ui';
import { FullScreenLoader } from './FullScreenLoader';

/**
 * The RequireOnboarding guard: loads `/me` (which provisions the profile on first call) and sends
 * anyone who hasn't finished onboarding there. If core-api can't be reached it shows a retry state
 * instead of crashing, with an escape hatch so the shell is still usable while the backend is down.
 */
export function ProfileGate({ children }: { children: ReactNode }) {
  const me = useMe();
  const { signOut } = useAuth();
  const [bypass, setBypass] = useState(false);

  if (me.isPending && !bypass) return <FullScreenLoader label="Loading your profile" />;

  if (me.isError && !bypass) {
    const detail =
      me.error instanceof ApiError ? `${me.error.message} (HTTP ${me.error.status})` : me.error.message;
    return (
      <Screen>
        <EmptyState
          icon="cloud-offline-outline"
          title="Couldn't load your profile"
          message={`FilmFuse's server didn't answer. ${detail}`}
        />
        <View className="w-full max-w-sm gap-3 self-center pb-6">
          <Button title="Try again" loading={me.isFetching} onPress={() => void me.refetch()} />
          <Button title="Continue anyway" variant="secondary" onPress={() => setBypass(true)} />
          <Button title="Sign out" variant="ghost" onPress={() => void signOut()} />
          <Body muted className="text-center text-xs">
            Is core-api running? Check EXPO_PUBLIC_API_BASE_URL.
          </Body>
        </View>
      </Screen>
    );
  }

  if (me.data && !me.data.onboardingCompletedAt) return <Redirect href="/onboarding" />;

  return <>{children}</>;
}

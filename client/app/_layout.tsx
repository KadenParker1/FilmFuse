import '../global.css';

import { useEffect } from 'react';
import { Stack } from 'expo-router';
import { RecoveryRedirect } from '../components/RecoveryRedirect';
import { StatusBar } from 'expo-status-bar';
import * as SplashScreen from 'expo-splash-screen';
import { useFonts } from 'expo-font';
import { QueryClientProvider } from '@tanstack/react-query';
import { Fraunces_600SemiBold } from '@expo-google-fonts/fraunces/600SemiBold';
import { Fraunces_700Bold } from '@expo-google-fonts/fraunces/700Bold';
import { Inter_400Regular } from '@expo-google-fonts/inter/400Regular';
import { Inter_500Medium } from '@expo-google-fonts/inter/500Medium';
import { Inter_600SemiBold } from '@expo-google-fonts/inter/600SemiBold';
import { queryClient } from '../lib/queryClient';
import { AuthProvider } from '../auth/AuthProvider';
import { useAuth } from '../auth/useAuth';
import { ThemeRoot } from '../components/ThemeRoot';
import { FullScreenLoader } from '../components/FullScreenLoader';
import { useScheme } from '../theme/useTheme';

void SplashScreen.preventAutoHideAsync().catch(() => {
  // already hidden (e.g. fast refresh) — harmless
});

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({
    Fraunces_600SemiBold,
    Fraunces_700Bold,
    Inter_400Regular,
    Inter_500Medium,
    Inter_600SemiBold,
  });
  const ready = fontsLoaded || !!fontError;

  useEffect(() => {
    if (ready) void SplashScreen.hideAsync().catch(() => {});
  }, [ready]);

  if (!ready) return null;

  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <ThemeRoot>
          <RootNavigator />
        </ThemeRoot>
      </AuthProvider>
    </QueryClientProvider>
  );
}

/**
 * Route guards. Signed out → only (auth). Signed in → (onboarding) and (tabs); the (tabs) layout
 * then checks `/me` and sends unfinished profiles to onboarding (it tolerates the API being down).
 * Invite links (`/invite/<token>`) and profiles (`/user/<id>`) are reachable either way.
 */
function RootNavigator() {
  const { session, loading } = useAuth();
  const scheme = useScheme();

  if (loading) return <FullScreenLoader />;
  const signedIn = !!session;

  return (
    <>
      <StatusBar style={scheme === 'dark' ? 'light' : 'dark'} />
      <Stack screenOptions={{ headerShown: false, contentStyle: { backgroundColor: 'transparent' } }}>
        <Stack.Protected guard={signedIn} redirectTo="/sign-in">
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="(onboarding)" />
          <Stack.Screen name="movie/[id]" />
          <Stack.Screen name="profile/edit" />
          <Stack.Screen name="settings/blocked" />
          <Stack.Screen name="settings/comfort" />
          <Stack.Screen name="groups/new" />
          <Stack.Screen name="groups/[id]" />
          <Stack.Screen name="sessions/new" />
          <Stack.Screen name="sessions/[id]" />
        </Stack.Protected>
        <Stack.Protected guard={!signedIn} redirectTo="/">
          <Stack.Screen name="(auth)" />
        </Stack.Protected>
        {/* Unguarded: OAuth / email-link redirects land here signed out and leave signed in. */}
        <Stack.Screen name="auth/callback" />
        <Stack.Screen name="auth/reset-password" />
        {/* Unguarded: signed out it saves the token and sends you to sign in; signed in it redeems. */}
        <Stack.Screen name="invite/[token]" />
        {/* Unguarded so email links survive a cold, signed-out load: it saves the path and sends you to sign in. */}
        <Stack.Screen name="user/[id]" />
      </Stack>
      <RecoveryRedirect />
    </>
  );
}

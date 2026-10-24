import '../global.css';

import { useEffect } from 'react';
import { Stack } from 'expo-router';
import { RecoveryRedirect } from '../components/RecoveryRedirect';
import { StatusBar } from 'expo-status-bar';
import * as SplashScreen from 'expo-splash-screen';
import { useFonts } from 'expo-font';
import { QueryClientProvider } from '@tanstack/react-query';
import { BricolageGrotesque_500Medium } from '@expo-google-fonts/bricolage-grotesque/500Medium';
import { BricolageGrotesque_700Bold } from '@expo-google-fonts/bricolage-grotesque/700Bold';
import { BricolageGrotesque_800ExtraBold } from '@expo-google-fonts/bricolage-grotesque/800ExtraBold';
import { Figtree_400Regular } from '@expo-google-fonts/figtree/400Regular';
import { Figtree_500Medium } from '@expo-google-fonts/figtree/500Medium';
import { Figtree_600SemiBold } from '@expo-google-fonts/figtree/600SemiBold';
import { Figtree_700Bold } from '@expo-google-fonts/figtree/700Bold';
import { queryClient } from '../lib/queryClient';
import { AuthProvider } from '../auth/AuthProvider';
import { useAuth } from '../auth/useAuth';
import { ThemeRoot } from '../components/ThemeRoot';
import { FullScreenLoader } from '../components/FullScreenLoader';
import { GuardedLinkSaver } from '../components/GuardedLinkSaver';
import { QuickRateProvider } from '../components/rating/QuickRateProvider';
import { AppearanceProvider } from '../theme/AppearanceProvider';
import { stackScreenOptions } from '../theme/navigation';
import { statusBarStyle, useTheme } from '../theme/useTheme';

void SplashScreen.preventAutoHideAsync().catch(() => {
  // already hidden (e.g. fast refresh) — harmless
});

export default function RootLayout() {
  const [fontsLoaded, fontError] = useFonts({
    BricolageGrotesque_500Medium,
    BricolageGrotesque_700Bold,
    BricolageGrotesque_800ExtraBold,
    Figtree_400Regular,
    Figtree_500Medium,
    Figtree_600SemiBold,
    Figtree_700Bold,
  });
  const ready = fontsLoaded || !!fontError;

  useEffect(() => {
    if (ready) void SplashScreen.hideAsync().catch(() => {});
  }, [ready]);

  if (!ready) return null;

  return (
    <AppearanceProvider>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <ThemeRoot>
            <QuickRateProvider>
              <RootNavigator />
            </QuickRateProvider>
          </ThemeRoot>
        </AuthProvider>
      </QueryClientProvider>
    </AppearanceProvider>
  );
}

/**
 * Route guards. Signed out → only (auth). Signed in → (onboarding) and (tabs); the (tabs) layout
 * then checks `/me` and sends unfinished profiles to onboarding (it tolerates the API being down).
 * Invite links (`/invite/<token>`), profiles (`/user/<id>`) and ratings (`/ratings/<id>`) are
 * reachable either way. Session and group links stay guarded: GuardedLinkSaver remembers one a
 * signed-out visitor opened, and PendingLinkRedeemer opens it after sign-in (and onboarding).
 */
function RootNavigator() {
  const { session, loading } = useAuth();
  const { scheme, colors } = useTheme();

  if (loading) return <FullScreenLoader />;
  const signedIn = !!session;

  return (
    <>
      <GuardedLinkSaver signedIn={signedIn} />
      <StatusBar style={statusBarStyle(scheme)} />
      <Stack screenOptions={stackScreenOptions(colors.bg)}>
        <Stack.Protected guard={signedIn} redirectTo="/sign-in">
          <Stack.Screen name="(tabs)" />
          <Stack.Screen name="(onboarding)" />
          <Stack.Screen name="movie/[id]" />
          <Stack.Screen name="movie/[id]/ratings" />
          <Stack.Screen name="user/[id]/ratings" />
          <Stack.Screen name="profile/edit" />
          <Stack.Screen name="settings/blocked" />
          <Stack.Screen name="settings/comfort" />
          <Stack.Screen name="settings/notifications" />
          <Stack.Screen name="settings/appearance" />
          <Stack.Screen name="groups/new" />
          <Stack.Screen name="groups/[id]" />
          <Stack.Screen name="sessions/new" />
          <Stack.Screen name="sessions/[id]" />
          <Stack.Screen name="taste/index" />
          <Stack.Screen name="taste/history" />
          <Stack.Screen name="taste/recalibrate" />
          {/* Admins only: app/admin/_layout shows the not-found screen to everyone else. */}
          <Stack.Screen name="admin" />
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
        {/* Unguarded for the same reason: comment emails link here. */}
        <Stack.Screen name="ratings/[id]" />
      </Stack>
      <RecoveryRedirect />
    </>
  );
}

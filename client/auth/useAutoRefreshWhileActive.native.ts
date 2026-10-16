import { useEffect } from 'react';
import { AppState } from 'react-native';
import { supabase } from '../lib/supabase';

/**
 * Native: refresh tokens only while the app is foregrounded (Supabase's recommended setup for
 * React Native), so a backgrounded app doesn't wake up to refresh and a resumed one catches up.
 */
export function useAutoRefreshWhileActive(): void {
  useEffect(() => {
    if (AppState.currentState === 'active') void supabase.auth.startAutoRefresh();
    const sub = AppState.addEventListener('change', (state) => {
      if (state === 'active') void supabase.auth.startAutoRefresh();
      else void supabase.auth.stopAutoRefresh();
    });
    return () => sub.remove();
  }, []);
}

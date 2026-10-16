/**
 * Web: Supabase keeps its session in localStorage. Guarded because static rendering (the web
 * export) evaluates this module in Node, where there is no `window`.
 */
import type { SupportedStorage } from '@supabase/supabase-js';

function storage(): Storage | null {
  try {
    return typeof window !== 'undefined' && window.localStorage ? window.localStorage : null;
  } catch {
    return null;
  }
}

export const authStorage: SupportedStorage = {
  getItem: (key) => storage()?.getItem(key) ?? null,
  setItem: (key, value) => storage()?.setItem(key, value),
  removeItem: (key) => storage()?.removeItem(key),
};

/** On web, OAuth / magic-link redirects land back with the session in the URL. */
export const detectSessionInUrl = true;

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { getAppearance, setAppearance } from '../lib/appearanceStorage';
import { DEFAULT_APPEARANCE, type AppearancePreference } from '../lib/appearancePreference';

type AppearanceContextValue = {
  preference: AppearancePreference;
  setPreference: (pref: AppearancePreference) => void;
};

const AppearanceContext = createContext<AppearanceContextValue>({
  preference: DEFAULT_APPEARANCE,
  setPreference: () => {},
});

/**
 * Holds the Appearance setting (Settings → Appearance). Starts dark and switches once the saved
 * choice has been read; a choice made before that read finishes wins.
 */
export function AppearanceProvider({ children }: { children: ReactNode }) {
  const [preference, setPref] = useState<AppearancePreference>(DEFAULT_APPEARANCE);
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    if (touched) return;
    let cancelled = false;
    void getAppearance().then((saved) => {
      if (!cancelled && saved) setPref(saved);
    });
    return () => {
      cancelled = true;
    };
  }, [touched]);

  const setPreference = useCallback((pref: AppearancePreference) => {
    setTouched(true);
    setPref(pref);
    void setAppearance(pref);
  }, []);

  const value = useMemo(() => ({ preference, setPreference }), [preference, setPreference]);
  return <AppearanceContext.Provider value={value}>{children}</AppearanceContext.Provider>;
}

/** The Appearance setting and its setter. Outside a provider: dark, and setting does nothing. */
export function useAppearance(): AppearanceContextValue {
  return useContext(AppearanceContext);
}

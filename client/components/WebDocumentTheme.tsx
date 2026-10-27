import { useEffect } from 'react';
import { Platform } from 'react-native';
import { APPEARANCE_KEY } from '../lib/appearancePreference';
import { palettes, type ColorScheme } from '../theme/tokens';

/**
 * Web only: the page behind the app (html/body - what shows under the iPhone notch, the home
 * indicator and Safari's rubber-band overscroll) and the browser chrome (`theme-color`) follow
 * the active colour scheme. app/+html.tsx paints the dark default before any JS runs; this keeps
 * it in step afterwards (Settings → Appearance, or "System" following the OS).
 */
export function applyDocumentTheme(doc: Document, scheme: ColorScheme): void {
  const bg = palettes[scheme].bg;
  doc.documentElement.style.backgroundColor = bg;
  doc.documentElement.style.colorScheme = scheme;
  if (doc.body) doc.body.style.backgroundColor = bg;
  doc.querySelectorAll('meta[name="theme-color"]').forEach((meta) => meta.setAttribute('content', bg));
}

/** Renders nothing; applies the scheme to the document on web. A no-op on native. */
export function WebDocumentTheme({ scheme, os = Platform.OS }: { scheme: ColorScheme; os?: string }) {
  useEffect(() => {
    if (os !== 'web' || typeof document === 'undefined') return;
    applyDocumentTheme(document, scheme);
  }, [scheme, os]);
  return null;
}

/**
 * An inline <head> script for app/+html.tsx: applies the saved Appearance before first paint, so
 * someone who chose Light doesn't see a dark flash (and a dark notch strip) while the bundle loads.
 * Mirrors resolveScheme(): dark unless the saved choice is "light", or "system" with a light OS.
 * Any failure (storage blocked, old browser) leaves the dark default from the static CSS.
 */
export function documentThemeScript(): string {
  const dark = JSON.stringify(palettes.dark.bg);
  const light = JSON.stringify(palettes.light.bg);
  const key = JSON.stringify(APPEARANCE_KEY);
  // Runs in the page with (w, d) = (window, document).
  const guard = `typeof window !== 'undefined'`;
  return (
    `(function(w,d){try{if(!(${guard}))return;var p=w.localStorage.getItem(${key});` +
    `var l=p==='light'||(p==='system'&&!!w.matchMedia&&w.matchMedia('(prefers-color-scheme: light)').matches);` +
    `var bg=l?${light}:${dark};var r=d.documentElement;r.style.backgroundColor=bg;r.style.colorScheme=l?'light':'dark';` +
    `var m=d.querySelectorAll('meta[name="theme-color"]');for(var i=0;i<m.length;i++)m[i].setAttribute('content',bg);` +
    `}catch(e){}})(window,document);`
  );
}

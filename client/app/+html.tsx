import type { ReactNode } from 'react';
import { ScrollViewStyleReset, useServerDocumentContext } from 'expo-router/html';
import { APP_NAME } from '../app.config';
import { documentThemeScript } from '../components/WebDocumentTheme';
import { palettes } from '../theme/tokens';

/**
 * The HTML document around every page of the static web export (Node only - never runs in the
 * browser or on native). Set up for iPhone Safari and "Add to Home Screen":
 *
 * - viewport-fit=cover lets the app draw under the notch / home indicator; the real insets come
 *   from env(safe-area-inset-*) through components/WebSafeAreaProvider. Zoom stays allowed
 *   (no maximum-scale / user-scalable=no) - inputs avoid iOS focus-zoom by being >= 16px
 *   (components/ui/TextInput) instead.
 * - The page fills the dynamic viewport (100dvh, falling back to 100%) and never scrolls itself
 *   (body overflow hidden from ScrollViewStyleReset): scroll views inside the app scroll, so the
 *   bottom tab bar stays pinned while Safari's toolbar collapses and expands.
 * - html is painted the theme background before JS runs, so the notch strip, the home-indicator
 *   strip and overscroll never flash white.
 * - Home-screen web app: manifest, apple-touch-icon, standalone + black-translucent status bar
 *   (content runs under the status bar; Screen pads the top inset).
 */
export const WEB_HEAD = {
  viewport: 'width=device-width, initial-scale=1, viewport-fit=cover',
  statusBarStyle: 'black-translucent',
  manifest: '/manifest.webmanifest',
  appleTouchIcon: '/apple-touch-icon.png',
} as const;

const DOCUMENT_CSS = `
html{background-color:${palettes.dark.bg};color-scheme:dark;-webkit-text-size-adjust:100%;text-size-adjust:100%}
body{overscroll-behavior:none;-webkit-tap-highlight-color:transparent}
@supports (height:100dvh){html,body,#root{height:100dvh}}
`;

export default function Root({ children }: { children: ReactNode }) {
  // Expo SDK 58: the bundle <script>s, stylesheets and favicon arrive as headNodes / bodyNodes -
  // a custom document that leaves them out exports pages that never hydrate.
  const { htmlAttributes, bodyAttributes, headNodes, bodyNodes } = useServerDocumentContext();
  return (
    <html lang="en" {...htmlAttributes}>
      <head>
        <meta charSet="utf-8" />
        <meta httpEquiv="X-UA-Compatible" content="IE=edge" />
        <meta name="viewport" content={WEB_HEAD.viewport} />
        <meta name="description" content="Rate movies, fuse your tastes with friends, and pick tonight's film together." />

        {/* Browser chrome colour; WebDocumentTheme keeps both in step with Settings → Appearance. */}
        <meta name="theme-color" media="(prefers-color-scheme: dark)" content={palettes.dark.bg} />
        <meta name="theme-color" media="(prefers-color-scheme: light)" content={palettes.dark.bg} />

        {/* Home-screen web app (iOS reads the apple-* tags; Android/Chrome the manifest). */}
        <link rel="manifest" href={WEB_HEAD.manifest} />
        <link rel="apple-touch-icon" sizes="180x180" href={WEB_HEAD.appleTouchIcon} />
        <meta name="apple-mobile-web-app-capable" content="yes" />
        <meta name="mobile-web-app-capable" content="yes" />
        <meta name="apple-mobile-web-app-status-bar-style" content={WEB_HEAD.statusBarStyle} />
        <meta name="apple-mobile-web-app-title" content={APP_NAME} />
        <meta name="application-name" content={APP_NAME} />
        <meta name="format-detection" content="telephone=no" />

        <ScrollViewStyleReset />
        <style id="filmfuse-document" dangerouslySetInnerHTML={{ __html: DOCUMENT_CSS }} />
        <script dangerouslySetInnerHTML={{ __html: documentThemeScript() }} />
        {headNodes}
      </head>
      <body {...bodyAttributes}>
        {children}
        {bodyNodes}
      </body>
    </html>
  );
}

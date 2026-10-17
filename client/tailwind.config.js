// Colours are CSS variables set from theme/tokens.ts by components/ThemeRoot.tsx, so one class
// (e.g. `bg-surface`) works in both colour schemes. Keep the token list in sync with tokens.ts.
const color = (name) => `rgb(var(--color-${name}) / <alpha-value>)`;

/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./app/**/*.{js,jsx,ts,tsx}', './components/**/*.{js,jsx,ts,tsx}', './auth/**/*.{js,jsx,ts,tsx}'],
  presets: [require('nativewind/preset')],
  // 'class', not the default 'media': on web NativeWind sets the colour scheme itself once its
  // darkMode flag lands in <head>, and with 'media' that throws ("Cannot manually set color scheme")
  // and crashes the dev server page. We don't use `dark:` variants - ThemeRoot switches palettes
  // via CSS variables from React Native's useColorScheme() - so 'class' changes nothing visually.
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        bg: color('bg'),
        surface: color('surface'),
        raised: color('raised'),
        border: color('border'),
        text: color('text'),
        muted: color('muted'),
        accent: color('accent'),
        'on-accent': color('on-accent'),
        'accent-soft': color('accent-soft'),
        'accent-ink': color('accent-ink'),
        accent2: color('accent2'),
        'on-accent2': color('on-accent2'),
        success: color('success'),
        'success-soft': color('success-soft'),
        danger: color('danger'),
        'on-danger': color('on-danger'),
        'danger-soft': color('danger-soft'),
      },
      // Bricolage Grotesque (display) + Figtree (body); names match theme/tokens.ts `fonts`.
      fontFamily: {
        'display-medium': ['BricolageGrotesque_500Medium'],
        display: ['BricolageGrotesque_700Bold'],
        'display-bold': ['BricolageGrotesque_800ExtraBold'],
        body: ['Figtree_400Regular'],
        'body-medium': ['Figtree_500Medium'],
        'body-semibold': ['Figtree_600SemiBold'],
        'body-bold': ['Figtree_700Bold'],
      },
    },
  },
  plugins: [],
};

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
        'surface-raised': color('surface-raised'),
        border: color('border'),
        text: color('text'),
        'text-muted': color('text-muted'),
        gold: color('gold'),
        'on-gold': color('on-gold'),
        velvet: color('velvet'),
        'on-velvet': color('on-velvet'),
        success: color('success'),
      },
      fontFamily: {
        display: ['Fraunces_600SemiBold'],
        'display-bold': ['Fraunces_700Bold'],
        body: ['Inter_400Regular'],
        'body-medium': ['Inter_500Medium'],
        'body-semibold': ['Inter_600SemiBold'],
      },
    },
  },
  plugins: [],
};

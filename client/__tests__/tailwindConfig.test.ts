// Regression: with the default darkMode 'media', NativeWind on web throws "Cannot manually set color
// scheme, as dark mode is type 'media'" as soon as the dev server injects its flags (found in a real
// browser run, invisible to jsdom tests). Theming relies on CSS variables, not `dark:` variants.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const config = require('../tailwind.config.js');

describe('tailwind config', () => {
  it("uses darkMode 'class' so NativeWind web can manage the colour scheme", () => {
    expect(config.darkMode).toBe('class');
  });
});

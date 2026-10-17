/**
 * Options for every Stack navigator: the opaque app background on each screen. A transparent card
 * (the old setting) let the screen underneath show through - and on web, during a transition or
 * with a transparent presentation, take clicks meant for the screen on top (bug 5: taps on a movie
 * page opened movies from the Discover grid below).
 */
export function stackScreenOptions(bg: string) {
  return { headerShown: false, contentStyle: { backgroundColor: bg } } as const;
}

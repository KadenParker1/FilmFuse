/**
 * A controllable expo-router stand-in for screen tests:
 *   jest.mock('expo-router', () => require('../test-utils/router').expoRouterMock);
 * then set `routerState.params` / inspect `routerState.router` in tests.
 */
import { useEffect } from 'react';

export const routerState = {
  params: {} as Record<string, string | string[] | undefined>,
  pathname: '/',
  navReady: true,
  /** Whether the screen under test is focused (drives the useFocusEffect stand-in; rerender after changing). */
  focused: true,
  router: {
    push: jest.fn(),
    replace: jest.fn(),
    back: jest.fn(),
    canGoBack: jest.fn(() => true),
  },
  reset() {
    this.params = {};
    this.pathname = '/';
    this.navReady = true;
    this.focused = true;
    Object.values(this.router).forEach((fn) => fn.mockClear());
    this.router.canGoBack.mockImplementation(() => true);
  },
};

export const expoRouterMock = {
  useRouter: () => routerState.router,
  useLocalSearchParams: () => routerState.params,
  usePathname: () => routerState.pathname,
  useRootNavigationState: () => (routerState.navReady ? { key: 'root' } : undefined),
  /** Runs the effect while `routerState.focused`; its cleanup runs on blur (rerender) or unmount. */
  useFocusEffect: (effect: () => undefined | void | (() => void)) => {
    const focused = routerState.focused;
    useEffect(() => (focused ? effect() : undefined), [effect, focused]);
  },
  Stack: { Screen: () => null },
  Redirect: () => null,
  Link: ({ children }: { children: unknown }) => children,
};

import type { ReactElement, ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, renderHook } from '@testing-library/react-native';

/**
 * A fresh client per test: no retries (failures surface immediately) and no garbage collection —
 * gc timers would otherwise keep a single-file jest run alive.
 */
export function createTestQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: Infinity, staleTime: 0 },
      mutations: { retry: false, gcTime: Infinity },
    },
  });
}

export function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

/** render() inside a QueryClientProvider; returns the client for cache assertions. */
export async function renderWithClient(ui: ReactElement, client: QueryClient = createTestQueryClient()) {
  const utils = await render(ui, { wrapper: createWrapper(client) });
  return { ...utils, client };
}

/** renderHook() inside a QueryClientProvider. */
export async function renderHookWithClient<R>(hook: () => R, client: QueryClient = createTestQueryClient()) {
  const utils = await renderHook(hook, { wrapper: createWrapper(client) });
  return { ...utils, client };
}

/** A minimal fetch Response. */
export function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(body),
    blob: () => Promise.resolve(body),
  } as unknown as Response;
}

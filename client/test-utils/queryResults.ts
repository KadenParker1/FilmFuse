/** Hand-built TanStack Query results for screens whose hooks are mocked. */
import type { PageDTO } from '../types/api';

export function infiniteResult<T>(overrides: Partial<Record<string, unknown>> & { items?: T[] } = {}) {
  const { items, ...rest } = overrides;
  const pages: PageDTO<T>[] | undefined = items ? [{ items, nextCursor: null }] : undefined;
  return {
    data: pages ? { pages, pageParams: [null] } : undefined,
    isPending: !pages,
    isError: false,
    error: null,
    fetchStatus: pages ? 'idle' : 'fetching',
    isFetching: false,
    hasNextPage: false,
    isFetchingNextPage: false,
    fetchNextPage: jest.fn(),
    refetch: jest.fn(),
    ...rest,
  };
}

export function queryResult<T>(overrides: Partial<Record<string, unknown>> & { data?: T } = {}) {
  const hasData = 'data' in overrides && overrides.data !== undefined;
  return {
    data: undefined,
    isPending: !hasData,
    isSuccess: hasData,
    isError: false,
    error: null,
    isFetching: false,
    refetch: jest.fn(),
    ...overrides,
  };
}

/** A hand-built useMutation result; `mutate` / `mutateAsync` are jest mocks. */
export function mutationResult(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    mutate: jest.fn(),
    mutateAsync: jest.fn(() => Promise.resolve(undefined)),
    reset: jest.fn(),
    isPending: false,
    isError: false,
    isSuccess: false,
    isIdle: true,
    error: null as unknown,
    data: undefined as unknown,
    variables: undefined as unknown,
    ...overrides,
  };
}

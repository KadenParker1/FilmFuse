/**
 * Answers mocked `fetch` calls by method + path (query string included), for screen tests that run
 * the real hooks:  const api = serveRoutes(fetchMock, [{ path: '/me', body: profile }]);
 * Earlier routes win; a route with `times` stops matching once used up. Unmatched calls get a 500.
 */
import { jsonResponse } from './render';

export const API_BASE = 'http://localhost:8080/api/v1';

export type Route = {
  method?: string;
  /** Exact path (with query) or a pattern tested against it. */
  path: string | RegExp;
  status?: number;
  /** The JSON answer; a function gets the parsed request body. */
  body?: unknown | ((requestBody: unknown) => unknown);
  /** How many calls it answers; unlimited when omitted. */
  times?: number;
  /** Never resolves (to inspect in-flight state). */
  hang?: boolean;
};

export type Served = {
  /** Every call made so far as { method, path, body }. */
  calls: () => { method: string; path: string; body: unknown }[];
  /** Calls to one method + path. */
  callsTo: (method: string, path: string | RegExp) => { method: string; path: string; body: unknown }[];
  /** Adds routes in front of the existing ones. */
  use: (...routes: Route[]) => void;
};

export function serveRoutes(fetchMock: jest.Mock, initial: Route[] = []): Served {
  let routes = initial.map((r) => ({ ...r }));
  const log: { method: string; path: string; body: unknown }[] = [];
  fetchMock.mockImplementation((url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET';
    const path = url.replace(API_BASE, '');
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : undefined;
    log.push({ method, path, body });
    const route = routes.find(
      (r) =>
        (r.method ?? 'GET') === method &&
        (typeof r.path === 'string' ? r.path === path : r.path.test(path)) &&
        (r.times === undefined || r.times > 0),
    );
    if (!route) return Promise.resolve(jsonResponse(500, { message: `unexpected ${method} ${path}` }));
    if (route.times !== undefined) route.times -= 1;
    if (route.hang) return new Promise(() => {});
    const answer = typeof route.body === 'function' ? (route.body as (b: unknown) => unknown)(body) : route.body;
    return Promise.resolve(jsonResponse(route.status ?? 200, answer));
  });
  const matches = (path: string, p: string | RegExp) => (typeof p === 'string' ? p === path : p.test(path));
  return {
    calls: () => log,
    callsTo: (method, path) => log.filter((c) => c.method === method && matches(c.path, path)),
    use: (...more) => {
      routes = [...more.map((r) => ({ ...r })), ...routes];
    },
  };
}

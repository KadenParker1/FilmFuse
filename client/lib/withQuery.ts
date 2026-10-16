/** `path?k=v&…` with encoded values; null, undefined and empty-string params are left out. */
export function withQuery(path: string, params: Record<string, string | number | null | undefined>): string {
  const qs = Object.entries(params)
    .filter((e): e is [string, string | number] => e[1] !== null && e[1] !== undefined && e[1] !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(String(v))}`)
    .join('&');
  return qs ? `${path}?${qs}` : path;
}

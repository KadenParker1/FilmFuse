/**
 * Development only: a phone (or an Android emulator) can't reach the dev machine's `localhost`, but
 * it already reaches Metro at `Constants.expoConfig.hostUri` (e.g. `192.168.1.20:8081`). So a
 * `localhost` / `127.0.0.1` API or Supabase URL from .env.local is pointed at that same machine,
 * keeping its port and path: `http://localhost:8090/api/v1` → `http://192.168.1.20:8090/api/v1`.
 *
 * Left alone: release builds (no hostUri), URLs that aren't local, and hostUris that aren't a LAN
 * address (`localhost` on the iOS Simulator / web - already right - or an `exp.direct` tunnel,
 * which only forwards Metro's port).
 */
const LOOPBACK = /^(localhost|127\.0\.0\.1)$/i;
const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;

/** The LAN host Metro was reached on, or null when there is nothing useful to rewrite to. */
export function lanHostFromHostUri(hostUri: string | null | undefined): string | null {
  if (!hostUri) return null;
  const host = hostUri.replace(/^[a-z]+:\/\//i, '').split(/[/:]/)[0] ?? '';
  if (!host || LOOPBACK.test(host)) return null;
  if (IPV4.test(host) || host.toLowerCase().endsWith('.local')) return host;
  return null;
}

/** `url` with a loopback host swapped for the dev machine's LAN host (see above). */
export function resolveDevUrl(url: string, hostUri: string | null | undefined): string {
  const lanHost = lanHostFromHostUri(hostUri);
  if (!lanHost || !url) return url;
  return url.replace(/^(https?:\/\/)(localhost|127\.0\.0\.1)(?=[:/]|$)/i, `$1${lanHost}`);
}

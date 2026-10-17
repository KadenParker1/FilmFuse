// Realtime e2e: member subscribes to private channel session:<id>; a refresh via core-api must
// broadcast session_updated. A non-member must be refused. Uses the client's installed supabase-js.
import { createRequire } from 'node:module';
const require = createRequire(new URL('../../client/package.json', import.meta.url).pathname);
const { createClient } = require('@supabase/supabase-js');

const SUPA = 'http://127.0.0.1:55321';
const API = 'http://127.0.0.1:8090/api/v1';
const { ANON_KEY, MEMBER_EMAIL, SESSION_ID } = process.env;
const PASSWORD = 'correct-horse-9';

async function client(email) {
  const c = createClient(SUPA, ANON_KEY, { auth: { persistSession: false } });
  const { data, error } = await c.auth.signInWithPassword({ email, password: PASSWORD });
  if (error) throw error;
  await c.realtime.setAuth(data.session.access_token);
  return { c, token: data.session.access_token };
}

function subscribe(c, label) {
  return new Promise((resolve) => {
    const events = [];
    const ch = c
      .channel(`session:${SESSION_ID}`, { config: { private: true } })
      .on('broadcast', { event: 'session_updated' }, (m) => events.push(m.payload))
      .subscribe((status, err) => {
        if (['SUBSCRIBED', 'CHANNEL_ERROR', 'TIMED_OUT', 'CLOSED'].includes(status)) resolve({ status, err, events, ch });
      });
    setTimeout(() => resolve({ status: 'NO_STATUS', events, ch }), 8000);
  });
}

const member = await client(MEMBER_EMAIL);
const sub = await subscribe(member.c, 'member');
console.log('member subscribe status:', sub.status, sub.err?.message ?? '');

// Outsider: fresh signup, not in the session
const outsiderEmail = `rt-outsider-${Date.now()}@example.com`;
const oc = createClient(SUPA, ANON_KEY, { auth: { persistSession: false } });
const { data: od } = await oc.auth.signUp({ email: outsiderEmail, password: PASSWORD });
await oc.realtime.setAuth(od.session.access_token);
const osub = await subscribe(oc, 'outsider');
console.log('outsider subscribe status:', osub.status);

const res = await fetch(`${API}/sessions/${SESSION_ID}/refresh`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${member.token}`, Accept: 'application/json' },
});
console.log('refresh http:', res.status);
await new Promise((r) => setTimeout(r, 3000));
console.log('member received:', JSON.stringify(sub.events));
console.log('outsider received:', JSON.stringify(osub.events));
const ok = sub.status === 'SUBSCRIBED' && sub.events.some((e) => e.type === 'refresh') && osub.events.length === 0;
console.log(ok ? 'REALTIME PASS' : 'REALTIME FAIL');
process.exit(ok ? 0 : 1);

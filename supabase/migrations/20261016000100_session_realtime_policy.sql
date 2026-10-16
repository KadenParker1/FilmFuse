-- Live session voting over Supabase Realtime (applied by the Supabase CLI: `supabase start` /
-- `supabase db reset` locally, `supabase db push` for the hosted project).
--
-- core-api broadcasts `session_updated` ({sessionId, type}, no session data) to the PRIVATE topic
-- `session:<sessionId>` after every vote / refresh / pick (SupabaseRealtimeGateway). Clients join
-- that channel with `config: { private: true }`; Realtime then asks this RLS policy whether the
-- signed-in user may receive it. Without the policy, anyone who guessed a session id could listen.
--
-- Why the helper is in schema `private` and not `app`: schema `app` belongs to Flyway (core-api).
-- These migrations usually run before core-api has ever started, and Flyway refuses to migrate a
-- schema that already contains objects (functions count) but has no history table. The function
-- body is plpgsql, so app.rec_session_members only has to exist when it is called, not when this
-- file runs. `private` is not an exposed API schema, so the function is not callable over the
-- Data API either.

create schema if not exists private;
revoke all on schema private from public, anon;
grant usage on schema private to authenticated;

-- True when the signed-in user (auth.uid()) is a member of the session named by a realtime topic
-- of the form `session:<uuid>`; false for any other topic, a malformed id or no session.
-- SECURITY DEFINER: `authenticated` has no access to schema app; this function (owned by the
-- migrating role) reads exactly one membership row on its behalf. search_path is pinned so a
-- caller cannot shadow app / auth objects.
create or replace function private.is_session_member(topic text)
    returns boolean
    language plpgsql
    stable
    security definer
    set search_path = ''
as $$
declare
    target_session uuid;
begin
    if topic is null or topic !~* '^session:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        return false;
    end if;
    target_session := substring(topic from 9)::uuid;
    return exists (
        select 1
        from app.rec_session_members m
        where m.session_id = target_session
          and m.user_id = auth.uid()
    );
end;
$$;

revoke all on function private.is_session_member(text) from public, anon;
grant execute on function private.is_session_member(text) to authenticated;

-- Receive (SELECT) only: clients never send on these channels (no INSERT policy), so only
-- core-api's service-role broadcasts reach them.
drop policy if exists "session members receive session broadcasts" on realtime.messages;
create policy "session members receive session broadcasts"
    on realtime.messages
    for select
    to authenticated
    using (
        realtime.messages.extension = 'broadcast'
        and private.is_session_member((select realtime.topic()))
    );

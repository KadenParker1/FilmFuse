-- SECURITY.md #23: PENDING_VERIFICATION was ported from the old user-service but nothing ever set
-- or enforced it (Supabase Auth owns email verification). Drop it so the status column only holds
-- values core-api acts on. Any stray row behaved as ACTIVE already, so it becomes ACTIVE.

update app.profiles set status = 'ACTIVE' where status = 'PENDING_VERIFICATION';

alter table app.profiles drop constraint profiles_status_check;
alter table app.profiles add constraint profiles_status_check check (status in ('ACTIVE', 'SUSPENDED'));

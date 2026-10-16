-- Read-only role for python-ml-service (training / candidate retrieval). core-api owns all writes.
--
-- Created NOLOGIN with no password on purpose: LOGIN and the password are set out-of-band in
-- Supabase (SQL editor: `alter role ml_reader with login password '...'`) and never live in this
-- repo. The ML service then connects with ML_DATABASE_URL using that role.
--
-- Safe to run on vanilla Postgres (Testcontainers) and to re-run against a database where the
-- role already exists (roles are cluster-wide, not per-database).

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'ml_reader') then
        create role ml_reader nologin;
    end if;
end
$$;

grant usage on schema app to ml_reader;
grant select on all tables in schema app to ml_reader;

-- Future tables created by the migrating role (Flyway's user) are readable too.
alter default privileges in schema app grant select on tables to ml_reader;

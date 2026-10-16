-- Per-user settings. Phase 3 adds only the content comfort fields (hard filters for every
-- recommendation); streaming services, runtime/year defaults and email preferences come later.
--
-- A user without a row has the defaults: no certification limit, no content limits, unrated
-- content excluded wherever a limit applies. Same vocabulary as V6:
--   max_certification: 'G' | 'PG' | 'PG-13' | 'R' | 'NC-17' | null (no limit)
--   content_limits:    {"violence": "mild", ...} - lowercase dimension -> lowercase max level;
--                      a missing dimension means no limit ("strong").
create table app.user_settings (
    user_id           uuid primary key references app.profiles (id) on delete cascade,
    max_certification text
        check (max_certification is null or max_certification in ('G', 'PG', 'PG-13', 'R', 'NC-17')),
    content_limits    jsonb not null default '{}' check (jsonb_typeof(content_limits) = 'object'),
    allow_unrated     boolean not null default false,
    updated_at        timestamptz not null default now()
);

-- Violence and frightening limits start at "mild" (owner decision 2026-11-08). The content tagger never
-- rates a film "none" for those two (G/PG films carry cartoon peril), so a "none" limit matched almost
-- nothing and emptied group sessions. ContentFilter already raises "none" to "mild" when it reads a
-- filter (ContentDimension.lowestLimit); this rewrites the stored rows so the data says the same.

create function pg_temp.mild_floor(limits jsonb) returns jsonb language sql immutable as $$
    select coalesce(jsonb_object_agg(
               key,
               case when key in ('violence', 'frightening') and value = '"none"'::jsonb
                    then '"mild"'::jsonb else value end), '{}'::jsonb)
    from jsonb_each(limits)
$$;

update app.user_settings
set content_limits = pg_temp.mild_floor(content_limits)
where content_limits ->> 'violence' = 'none' or content_limits ->> 'frightening' = 'none';

update app.groups
set content_limits = pg_temp.mild_floor(content_limits)
where content_limits ->> 'violence' = 'none' or content_limits ->> 'frightening' = 'none';

-- Stored session filters (shown for history, re-applied to open sessions).
update app.rec_sessions
set effective_filter = jsonb_set(effective_filter, '{contentLimits}',
                                 pg_temp.mild_floor(effective_filter -> 'contentLimits'))
where jsonb_typeof(effective_filter -> 'contentLimits') = 'object'
  and (effective_filter -> 'contentLimits' ->> 'violence' = 'none'
       or effective_filter -> 'contentLimits' ->> 'frightening' = 'none');

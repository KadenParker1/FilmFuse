-- Supabase Storage bucket for profile photos (applied by the Supabase CLI: `supabase start` /
-- `supabase db reset` locally, `supabase db push` for the hosted project). Schema `app` is not
-- here - Flyway in core-api owns it.
--
-- Public bucket: photos are read through their public URL, which core-api stores in
-- app.profiles.profile_image_url. Objects live at `<userId>/<uuid>.jpg`.
--
-- No storage.objects policies on purpose: clients never write with their own credentials. core-api
-- (service-role key) issues a signed upload URL for one exact path after checking the caller, and
-- deletes old photos itself; reads need no policy because the bucket is public.

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('profile-photos', 'profile-photos', true, 2097152, array['image/jpeg'])
on conflict (id) do nothing;

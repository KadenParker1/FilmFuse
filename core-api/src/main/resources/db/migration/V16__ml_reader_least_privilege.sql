-- ml_reader (python-ml-service) gets only what recommendation work can use. V2 granted SELECT on
-- every app table; this takes back what holds personal or moderation data and no taste signal:
--   * profiles.email - the address notifications go to (column-level grant on the rest of profiles);
--   * invites        - token hashes and who invited whom;
--   * reports        - moderation reports and their free-text details.
-- Default privileges (V2) still grant SELECT on future tables; revoke here when one holds PII.

revoke select on app.profiles from ml_reader;
grant select (id, username, display_name, profile_image_url, bio, status, onboarding_step,
              onboarding_completed_at, created_at, updated_at) on app.profiles to ml_reader;

revoke select on app.invites from ml_reader;
revoke select on app.reports from ml_reader;

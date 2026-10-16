export type AuthMode = 'sign-in' | 'sign-up' | 'forgot';

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
export const MIN_PASSWORD = 8;

export type AuthFieldErrors = { email?: string; password?: string; confirm?: string };

/** A new password (sign-up or reset): 8+ characters, typed the same twice. */
export function validateNewPassword(password: string, confirm: string): AuthFieldErrors {
  const errs: AuthFieldErrors = {};
  if (password.length < MIN_PASSWORD) errs.password = `Use at least ${MIN_PASSWORD} characters`;
  if (confirm !== password) errs.confirm = "Passwords don't match";
  return errs;
}

/** Client-side checks before calling Supabase (it validates again). */
export function validateAuthForm(mode: AuthMode, email: string, password: string, confirm: string): AuthFieldErrors {
  const errs: AuthFieldErrors = {};
  if (!EMAIL_RE.test(email.trim())) errs.email = 'Enter a valid email address';
  if (mode === 'forgot') return errs;
  if (mode === 'sign-in' && password.length === 0) errs.password = 'Enter your password';
  if (mode === 'sign-up') Object.assign(errs, validateNewPassword(password, confirm));
  return errs;
}

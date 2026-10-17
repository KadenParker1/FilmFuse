import { GROUP_NAME_MAX, MAX_INVITEES } from '../../api/groups';
import { UNSAFE_TEXT_MESSAGE, isSafeText } from '../../lib/safeText';

export type GroupFormErrors = { name?: string; members?: string };

/** Mirrors CreateGroupDTO's rules: a 1–40 character safe-text name and at most 19 invited friends. */
export function validateGroupForm(name: string, memberIds: string[]): GroupFormErrors {
  const errors: GroupFormErrors = {};
  const trimmed = name.trim();
  if (!trimmed) errors.name = 'Give your group a name.';
  else if (trimmed.length > GROUP_NAME_MAX) errors.name = `Keep it to ${GROUP_NAME_MAX} characters.`;
  else if (!isSafeText(trimmed)) errors.name = UNSAFE_TEXT_MESSAGE;
  if (memberIds.length > MAX_INVITEES) errors.members = `A group can have up to ${MAX_INVITEES} other people.`;
  return errors;
}

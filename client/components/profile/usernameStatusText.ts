import type { UsernameStatus } from '../../api/usernames';

/** What the edit screen shows under the username field, and in which tone. */
export function usernameStatusText(status: UsernameStatus): { text: string; tone: 'muted' | 'good' | 'bad' } | null {
  switch (status) {
    case 'checking':
      return { text: 'Checking…', tone: 'muted' };
    case 'available':
      return { text: 'Available', tone: 'good' };
    case 'taken':
      return { text: 'Already taken', tone: 'bad' };
    case 'invalid':
      return { text: '3–30 characters: lowercase letters, numbers and _', tone: 'bad' };
    case 'error':
      return { text: "Couldn't check availability right now", tone: 'muted' };
    case 'unchanged':
      return { text: 'Your current username', tone: 'muted' };
    default:
      return null;
  }
}

import * as Clipboard from 'expo-clipboard';
import { INVITE_MESSAGE, type ShareOutcome } from './shareInviteTypes';

export { INVITE_MESSAGE, type ShareOutcome } from './shareInviteTypes';

/**
 * Web: the Web Share API where the browser has it (mobile browsers, Safari); otherwise — or if it
 * fails for any reason other than the user closing it — the link is copied to the clipboard.
 */
export async function shareInvite(url: string): Promise<ShareOutcome> {
  const nav: Navigator | undefined = typeof navigator !== 'undefined' ? navigator : undefined;
  if (nav && typeof nav.share === 'function') {
    try {
      await nav.share({ title: 'Join me on FilmFuse', text: INVITE_MESSAGE, url });
      return 'shared';
    } catch (e) {
      if ((e as { name?: string } | null)?.name === 'AbortError') return 'dismissed';
    }
  }
  await Clipboard.setStringAsync(url);
  return 'copied';
}

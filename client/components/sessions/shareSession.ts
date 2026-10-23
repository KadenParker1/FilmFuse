import * as Clipboard from 'expo-clipboard';
import { SESSION_SHARE_MESSAGE, type ShareOutcome } from './shareSessionTypes';

export { SESSION_SHARE_MESSAGE, type ShareOutcome } from './shareSessionTypes';

/**
 * Web: the Web Share API where the browser has it; otherwise - or if it fails for any reason other
 * than the user closing it - the link is copied to the clipboard (as shareInvite does).
 */
export async function shareSession(url: string): Promise<ShareOutcome> {
  const nav: Navigator | undefined = typeof navigator !== 'undefined' ? navigator : undefined;
  if (nav && typeof nav.share === 'function') {
    try {
      await nav.share({ title: 'Pick a movie with us', text: SESSION_SHARE_MESSAGE, url });
      return 'shared';
    } catch (e) {
      if ((e as { name?: string } | null)?.name === 'AbortError') return 'dismissed';
    }
  }
  await Clipboard.setStringAsync(url);
  return 'copied';
}

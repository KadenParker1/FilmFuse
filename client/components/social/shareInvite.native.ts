import { Platform, Share } from 'react-native';
import { INVITE_MESSAGE, type ShareOutcome } from './shareInviteTypes';

export { INVITE_MESSAGE, type ShareOutcome } from './shareInviteTypes';

/**
 * Native: the OS share sheet. iOS takes the link as `url` (shown as a rich link); Android only
 * shares `message`, so the link goes in the text there.
 */
export async function shareInvite(url: string): Promise<ShareOutcome> {
  const content = Platform.OS === 'ios' ? { message: INVITE_MESSAGE, url } : { message: `${INVITE_MESSAGE} ${url}` };
  const result = await Share.share(content, { dialogTitle: 'Invite friends to FilmFuse' });
  return result.action === Share.dismissedAction ? 'dismissed' : 'shared';
}

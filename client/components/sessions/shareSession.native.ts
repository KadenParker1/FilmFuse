import { Platform, Share } from 'react-native';
import { SESSION_SHARE_MESSAGE, type ShareOutcome } from './shareSessionTypes';

export { SESSION_SHARE_MESSAGE, type ShareOutcome } from './shareSessionTypes';

/** Native: the OS share sheet. iOS takes the link as `url`; Android only shares `message`. */
export async function shareSession(url: string): Promise<ShareOutcome> {
  const content =
    Platform.OS === 'ios' ? { message: SESSION_SHARE_MESSAGE, url } : { message: `${SESSION_SHARE_MESSAGE} ${url}` };
  const result = await Share.share(content, { dialogTitle: 'Share this movie session' });
  return result.action === Share.dismissedAction ? 'dismissed' : 'shared';
}

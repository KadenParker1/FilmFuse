import { ApiError } from '../../lib/apiClient';

/** Friendly copy for the redeem failures the server can answer with; null = generic error. */
export function inviteFailureCopy(error: unknown): { title: string; message: string } | null {
  if (!(error instanceof ApiError)) return null;
  switch (error.status) {
    case 404:
      return {
        title: "This invite isn't valid",
        message: 'The link may be mistyped, or the person who sent it is no longer available.',
      };
    case 410:
      return { title: 'This invite has expired', message: 'Invite links last 14 days. Ask your friend for a new one.' };
    case 400:
      return {
        title: "That's your own invite",
        message: 'Share it with friends instead. They become your friends as soon as they open it.',
      };
    default:
      return null;
  }
}

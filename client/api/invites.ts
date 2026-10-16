import { useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import type { InviteDTO, InviteRedeemResultDTO } from '../types/api';
import { applyFriendship, removeRequest } from './socialCache';

/** A fresh shareable invite link (valid 14 days, usable by several people). */
export function useCreateInvite() {
  return useMutation({ mutationFn: () => apiClient.post<InviteDTO>('/invites') });
}

/**
 * Redeems an invite token: the caller and the inviter become friends straight away. Errors: 404
 * unknown (or blocked), 410 expired, 400 your own link.
 */
export function useRedeemInvite() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (token: string) =>
      apiClient.post<InviteRedeemResultDTO>(`/invites/${encodeURIComponent(token)}/redeem`),
    onSuccess: ({ inviter }) => {
      applyFriendship(qc, inviter.id, 'FRIENDS');
      removeRequest(qc, 'INCOMING', inviter.id);
      removeRequest(qc, 'OUTGOING', inviter.id);
      void qc.invalidateQueries({ queryKey: queryKeys.friends });
      void qc.invalidateQueries({ queryKey: queryKeys.friendSuggestionsAll });
    },
  });
}

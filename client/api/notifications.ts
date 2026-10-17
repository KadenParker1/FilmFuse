import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import type { NotificationSettingsDTO } from '../types/api';

export const NOTIFICATIONS_PATH = '/me/settings/notifications';

export const fetchNotificationSettings = () => apiClient.get<NotificationSettingsDTO>(NOTIFICATIONS_PATH);

/** Which emails the caller gets (all on by default except the weekly digest). */
export function useNotificationSettings() {
  return useQuery({ queryKey: queryKeys.notificationSettings, queryFn: fetchNotificationSettings });
}

/** Replaces the email preferences. Optimistic (switches flip at once), rolled back on failure. */
export function useUpdateNotificationSettings() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: NotificationSettingsDTO) => apiClient.put<NotificationSettingsDTO>(NOTIFICATIONS_PATH, body),
    onMutate: async (body: NotificationSettingsDTO) => {
      await qc.cancelQueries({ queryKey: queryKeys.notificationSettings });
      const previous = qc.getQueryData<NotificationSettingsDTO>(queryKeys.notificationSettings);
      qc.setQueryData(queryKeys.notificationSettings, body);
      return { previous };
    },
    onSuccess: (settings) => qc.setQueryData(queryKeys.notificationSettings, settings),
    onError: (_error, _body, context) => {
      if (context?.previous) qc.setQueryData(queryKeys.notificationSettings, context.previous);
    },
  });
}

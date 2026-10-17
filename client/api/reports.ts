import { useMutation } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import type { CreateReportDTO } from '../types/api';

/** Optional report details are at most this long. */
export const REPORT_DETAILS_MAX = 500;

/**
 * Reports a rating, comment or person. Blank details are sent as null. Reporting the same thing
 * twice is fine (the server keeps one open report).
 */
export function useCreateReport() {
  return useMutation({
    mutationFn: ({ details, ...rest }: CreateReportDTO) => {
      const trimmed = details?.trim();
      const body: CreateReportDTO = { ...rest, details: trimmed ? trimmed : null };
      return apiClient.post<void>('/reports', body);
    },
  });
}

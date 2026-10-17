import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import { withQuery } from '../lib/withQuery';
import type { PageDTO, RecommendationDTO } from '../types/api';
import { filterInfinite, type InfinitePages } from './socialCache';
import { markTasteRebuilding } from './taste';

export const fetchRecommendations = (cursor?: string | null) =>
  apiClient.get<PageDTO<RecommendationDTO>>(withQuery('/me/recommendations', { cursor }));

/** "For you": personal picks from the caller's taste vector, filtered by their comfort settings. Paged. */
export function useRecommendations(options: { enabled?: boolean } = {}) {
  return useInfiniteQuery({
    queryKey: queryKeys.recommendations,
    queryFn: ({ pageParam }) => fetchRecommendations(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor,
    enabled: options.enabled ?? true,
  });
}

type DismissContext = { previous: InfinitePages<RecommendationDTO> | undefined };

/** "Not interested": the pick disappears at once and comes back if the server refuses. */
export function useDismissRecommendation() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (movieId: number) => apiClient.post<void>(`/me/recommendations/${movieId}/dismiss`),
    onMutate: async (movieId: number): Promise<DismissContext> => {
      await qc.cancelQueries({ queryKey: queryKeys.recommendations });
      const previous = qc.getQueryData<InfinitePages<RecommendationDTO>>(queryKeys.recommendations);
      qc.setQueryData<InfinitePages<RecommendationDTO>>(queryKeys.recommendations, (data) =>
        filterInfinite(data, (r) => r.movie.id !== movieId),
      );
      return { previous };
    },
    onError: (_error, _movieId, context) => {
      if (context?.previous) qc.setQueryData(queryKeys.recommendations, context.previous);
    },
    onSuccess: () => markTasteRebuilding(qc),
  });
}

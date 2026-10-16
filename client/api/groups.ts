import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { apiClient } from '../lib/apiClient';
import { queryKeys } from '../lib/queryKeys';
import type {
  AddMembersDTO,
  CreateGroupDTO,
  GroupDetailDTO,
  GroupSummaryDTO,
  UpdateGroupDTO,
} from '../types/api';

/** A group may hold 20 people including the owner and anyone invited. */
export const MAX_GROUP_MEMBERS = 20;
/** So a creator can pick at most 19 others. */
export const MAX_INVITEES = MAX_GROUP_MEMBERS - 1;
export const GROUP_NAME_MAX = 40;

const id = (value: string) => encodeURIComponent(value);

export const fetchGroups = () => apiClient.get<GroupSummaryDTO[]>('/groups');
export const fetchGroup = (groupId: string) => apiClient.get<GroupDetailDTO>(`/groups/${id(groupId)}`);

/** Caches a fresh detail and marks the list stale (names, counts, previews may have changed). */
function storeDetail(qc: QueryClient, group: GroupDetailDTO): void {
  qc.setQueryData(queryKeys.group(group.id), group);
  void qc.invalidateQueries({ queryKey: queryKeys.groupList });
}

/** The caller is no longer in `groupId`: drop its detail and its card. */
function forgetGroup(qc: QueryClient, groupId: string): void {
  qc.removeQueries({ queryKey: queryKeys.group(groupId), exact: true });
  qc.setQueryData<GroupSummaryDTO[]>(queryKeys.groupList, (list) => list?.filter((g) => g.id !== groupId));
  void qc.invalidateQueries({ queryKey: queryKeys.groupList });
  void qc.invalidateQueries({ queryKey: queryKeys.sessionListAll });
}

// ---- queries ------------------------------------------------------------------------------------

/** The caller's groups: active ones (most recent session first), then pending invites. */
export function useGroups() {
  return useQuery({ queryKey: queryKeys.groupList, queryFn: fetchGroups });
}

/** One group (the caller must be an active or invited member; otherwise 404). */
export function useGroup(groupId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.group(groupId ?? ''),
    queryFn: () => fetchGroup(groupId as string),
    enabled: !!groupId,
  });
}

// ---- mutations ----------------------------------------------------------------------------------

/** Creates a group; the caller becomes OWNER and every listed friend is invited. */
export function useCreateGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateGroupDTO) => apiClient.post<GroupDetailDTO>('/groups', body),
    onSuccess: (group) => storeDetail(qc, group),
  });
}

type UpdateGroupVars = { groupId: string; body: UpdateGroupDTO };

/** Owner only: rename, change emoji / colour, or set the group comfort cap. */
export function useUpdateGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, body }: UpdateGroupVars) => apiClient.patch<GroupDetailDTO>(`/groups/${id(groupId)}`, body),
    onSuccess: (group) => storeDetail(qc, group),
  });
}

/** Owner only: deletes the group for everyone. */
export function useDeleteGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (groupId: string) => apiClient.del<void>(`/groups/${id(groupId)}`),
    onSuccess: (_, groupId) => forgetGroup(qc, groupId),
  });
}

type AddMembersVars = { groupId: string; userIds: string[] };

/** Invites more of the caller's friends (any active member may). */
export function useAddMembers() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, userIds }: AddMembersVars) => {
      const body: AddMembersDTO = { userIds };
      return apiClient.post<GroupDetailDTO>(`/groups/${id(groupId)}/members`, body);
    },
    onSuccess: (group) => storeDetail(qc, group),
  });
}

type MemberVars = { groupId: string; userId: string };

/** Owner removes someone else (or withdraws their invite). */
export function useRemoveMember() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, userId }: MemberVars) =>
      apiClient.del<void>(`/groups/${id(groupId)}/members/${id(userId)}`),
    onSuccess: (_, { groupId, userId }) => {
      qc.setQueryData<GroupDetailDTO>(queryKeys.group(groupId), (g) =>
        g ? { ...g, members: g.members.filter((m) => m.user.id !== userId) } : g,
      );
      // The effective filter may loosen once they're gone.
      void qc.invalidateQueries({ queryKey: queryKeys.group(groupId) });
      void qc.invalidateQueries({ queryKey: queryKeys.groupList });
    },
  });
}

/** The caller leaves (`userId` is their own id). An owner leaving hands ownership on. */
export function useLeaveGroup() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ groupId, userId }: MemberVars) =>
      apiClient.del<void>(`/groups/${id(groupId)}/members/${id(userId)}`),
    onSuccess: (_, { groupId }) => forgetGroup(qc, groupId),
  });
}

/** Joins a group the caller was invited to. */
export function useAcceptGroupInvite() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (groupId: string) => apiClient.post<GroupDetailDTO>(`/groups/${id(groupId)}/invites/accept`),
    onSuccess: (group) => storeDetail(qc, group),
  });
}

/** Declines a group invite; the group disappears for the caller. */
export function useDeclineGroupInvite() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (groupId: string) => apiClient.post<void>(`/groups/${id(groupId)}/invites/decline`),
    onSuccess: (_, groupId) => forgetGroup(qc, groupId),
  });
}

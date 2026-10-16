jest.mock('../lib/supabase', () => ({
  supabase: { auth: { getSession: jest.fn(() => Promise.resolve({ data: { session: { access_token: 'tok' } } })) } },
}));

import { act, waitFor } from '@testing-library/react-native';
import { notifyManager } from '@tanstack/react-query';
import { useComfortSettings, useUpdateComfortSettings } from '../api/comfort';
import {
  useAcceptGroupInvite,
  useAddMembers,
  useCreateGroup,
  useDeclineGroupInvite,
  useDeleteGroup,
  useGroup,
  useGroups,
  useLeaveGroup,
  useRemoveMember,
  useUpdateGroup,
} from '../api/groups';
import { queryKeys } from '../lib/queryKeys';
import { makeComfort, makeGroup, makeGroupSummary } from '../test-utils/fixtures';
import { createTestQueryClient, jsonResponse, renderHookWithClient } from '../test-utils/render';
import type { GroupDetailDTO, GroupSummaryDTO } from '../types/api';

notifyManager.setScheduler((cb) => cb());

const BASE = 'http://localhost:8080/api/v1';
const fetchMock = jest.fn();
beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
});
const call = (i = 0) => ({
  url: fetchMock.mock.calls[i][0] as string,
  init: fetchMock.mock.calls[i][1] as RequestInit,
});
const body = (i = 0) => JSON.parse(call(i).init.body as string);
const noContent = () => jsonResponse(204, undefined);
type Client = ReturnType<typeof createTestQueryClient>;
const isInvalidated = (client: Client, key: readonly unknown[]) => client.getQueryState(key)?.isInvalidated ?? false;

/** Seeds the group list, two group details and a session history list. */
function seed(client: Client) {
  client.setQueryData(queryKeys.groupList, [makeGroupSummary(), makeGroupSummary({ id: 'g2', name: 'Family' })]);
  client.setQueryData(queryKeys.group('g1'), makeGroup());
  client.setQueryData(queryKeys.group('g2'), makeGroup({ id: 'g2', name: 'Family' }));
  client.setQueryData(queryKeys.sessionList(null), { pages: [{ items: [], nextCursor: null }], pageParams: [null] });
}
const listIds = (client: Client) => client.getQueryData<GroupSummaryDTO[]>(queryKeys.groupList)?.map((g) => g.id);

describe('comfort settings', () => {
  it('useComfortSettings GETs /me/settings/comfort', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, makeComfort({ maxCertification: 'PG_13' })));
    const { result } = await renderHookWithClient(() => useComfortSettings());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/me/settings/comfort`);
    expect(result.current.data?.maxCertification).toBe('PG_13');
  });

  it('useUpdateComfortSettings PUTs the whole DTO, caches it and invalidates groups', async () => {
    const client = createTestQueryClient();
    seed(client);
    client.setQueryData(queryKeys.comfortSettings, makeComfort());
    const next = makeComfort({ maxCertification: 'R', contentLimits: { VIOLENCE: 'MILD' }, allowUnrated: true });
    fetchMock.mockResolvedValue(jsonResponse(200, next));
    const { result } = await renderHookWithClient(() => useUpdateComfortSettings(), client);
    await act(async () => {
      await result.current.mutateAsync(next);
    });
    expect(call().url).toBe(`${BASE}/me/settings/comfort`);
    expect(call().init.method).toBe('PUT');
    expect(body()).toEqual(next);
    expect(client.getQueryData(queryKeys.comfortSettings)).toEqual(next);
    expect(isInvalidated(client, queryKeys.group('g1'))).toBe(true);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
  });

  it('surfaces validation errors with fieldErrors', async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(400, { message: 'Validation failed', fieldErrors: { maxCertification: 'invalid' } }),
    );
    const { result } = await renderHookWithClient(() => useUpdateComfortSettings());
    await act(async () => {
      await result.current.mutateAsync(makeComfort()).catch(() => {});
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ status: 400, fieldErrors: { maxCertification: 'invalid' } });
  });
});

describe('group queries', () => {
  it('useGroups GETs /groups', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, [makeGroupSummary()]));
    const { result } = await renderHookWithClient(() => useGroups());
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/groups`);
    expect(result.current.data).toHaveLength(1);
  });

  it('useGroup GETs /groups/{id} (encoded) and is disabled without an id', async () => {
    fetchMock.mockResolvedValue(jsonResponse(200, makeGroup()));
    const { result } = await renderHookWithClient(() => useGroup('g 1'));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(call().url).toBe(`${BASE}/groups/g%201`);
    const idle = await renderHookWithClient(() => useGroup(undefined));
    expect(idle.result.current.fetchStatus).toBe('idle');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('useGroup surfaces a 404', async () => {
    fetchMock.mockResolvedValue(jsonResponse(404, { message: 'Group not found' }));
    const { result } = await renderHookWithClient(() => useGroup('gone'));
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error).toMatchObject({ status: 404 });
  });
});

describe('group mutations', () => {
  it('useCreateGroup POSTs, caches the detail and invalidates the list', async () => {
    const client = createTestQueryClient();
    seed(client);
    const created = makeGroup({ id: 'g9', name: 'New' });
    fetchMock.mockResolvedValue(jsonResponse(201, created));
    const { result } = await renderHookWithClient(() => useCreateGroup(), client);
    const dto = { name: 'New', emoji: '🍿', color: '#F5B942', memberIds: ['u2'] };
    await act(async () => {
      await result.current.mutateAsync(dto);
    });
    expect(call().url).toBe(`${BASE}/groups`);
    expect(call().init.method).toBe('POST');
    expect(body()).toEqual(dto);
    expect(client.getQueryData(queryKeys.group('g9'))).toEqual(created);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
  });

  it('useUpdateGroup PATCHes /groups/{id} and replaces the cached detail', async () => {
    const client = createTestQueryClient();
    seed(client);
    const updated = makeGroup({ name: 'Kids', comfortCap: { maxCertification: 'PG', contentLimits: {} } });
    fetchMock.mockResolvedValue(jsonResponse(200, updated));
    const { result } = await renderHookWithClient(() => useUpdateGroup(), client);
    await act(async () => {
      await result.current.mutateAsync({
        groupId: 'g1',
        body: { name: 'Kids', comfortCap: { maxCertification: 'PG', contentLimits: {} } },
      });
    });
    expect(call().url).toBe(`${BASE}/groups/g1`);
    expect(call().init.method).toBe('PATCH');
    expect(body()).toEqual({ name: 'Kids', comfortCap: { maxCertification: 'PG', contentLimits: {} } });
    expect(client.getQueryData<GroupDetailDTO>(queryKeys.group('g1'))?.name).toBe('Kids');
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
  });

  it('useDeleteGroup DELETEs and forgets the group everywhere', async () => {
    const client = createTestQueryClient();
    seed(client);
    fetchMock.mockResolvedValue(noContent());
    const { result } = await renderHookWithClient(() => useDeleteGroup(), client);
    await act(async () => {
      await result.current.mutateAsync('g1');
    });
    expect(call().url).toBe(`${BASE}/groups/g1`);
    expect(call().init.method).toBe('DELETE');
    expect(client.getQueryData(queryKeys.group('g1'))).toBeUndefined();
    expect(client.getQueryData(queryKeys.group('g2'))).toBeDefined();
    expect(listIds(client)).toEqual(['g2']);
    expect(isInvalidated(client, queryKeys.sessionList(null))).toBe(true);
  });

  it('useAddMembers POSTs the ids and caches the new detail', async () => {
    const client = createTestQueryClient();
    seed(client);
    const updated = makeGroup({ members: [] });
    fetchMock.mockResolvedValue(jsonResponse(200, updated));
    const { result } = await renderHookWithClient(() => useAddMembers(), client);
    await act(async () => {
      await result.current.mutateAsync({ groupId: 'g1', userIds: ['u4', 'u5'] });
    });
    expect(call().url).toBe(`${BASE}/groups/g1/members`);
    expect(call().init.method).toBe('POST');
    expect(body()).toEqual({ userIds: ['u4', 'u5'] });
    expect(client.getQueryData(queryKeys.group('g1'))).toEqual(updated);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
  });

  it('useRemoveMember DELETEs the member and drops them from the cached detail', async () => {
    const client = createTestQueryClient();
    seed(client);
    fetchMock.mockResolvedValue(noContent());
    const { result } = await renderHookWithClient(() => useRemoveMember(), client);
    await act(async () => {
      await result.current.mutateAsync({ groupId: 'g1', userId: 'u2' });
    });
    expect(call().url).toBe(`${BASE}/groups/g1/members/u2`);
    expect(call().init.method).toBe('DELETE');
    expect(client.getQueryData<GroupDetailDTO>(queryKeys.group('g1'))?.members.map((m) => m.user.id)).toEqual([
      'u1',
      'u3',
    ]);
    expect(isInvalidated(client, queryKeys.group('g1'))).toBe(true);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
    expect(listIds(client)).toEqual(['g1', 'g2']);
  });

  it('useLeaveGroup DELETEs the caller and forgets the group', async () => {
    const client = createTestQueryClient();
    seed(client);
    fetchMock.mockResolvedValue(noContent());
    const { result } = await renderHookWithClient(() => useLeaveGroup(), client);
    await act(async () => {
      await result.current.mutateAsync({ groupId: 'g2', userId: 'me' });
    });
    expect(call().url).toBe(`${BASE}/groups/g2/members/me`);
    expect(call().init.method).toBe('DELETE');
    expect(client.getQueryData(queryKeys.group('g2'))).toBeUndefined();
    expect(listIds(client)).toEqual(['g1']);
  });

  it('useAcceptGroupInvite POSTs accept and caches the now-active detail', async () => {
    const client = createTestQueryClient();
    seed(client);
    const joined = makeGroup({ id: 'g2', role: 'MEMBER', memberStatus: 'ACTIVE' });
    fetchMock.mockResolvedValue(jsonResponse(200, joined));
    const { result } = await renderHookWithClient(() => useAcceptGroupInvite(), client);
    await act(async () => {
      await result.current.mutateAsync('g2');
    });
    expect(call().url).toBe(`${BASE}/groups/g2/invites/accept`);
    expect(call().init.method).toBe('POST');
    expect(call().init.body).toBeUndefined();
    expect(client.getQueryData(queryKeys.group('g2'))).toEqual(joined);
    expect(isInvalidated(client, queryKeys.groupList)).toBe(true);
  });

  it('useDeclineGroupInvite POSTs decline and removes the group', async () => {
    const client = createTestQueryClient();
    seed(client);
    fetchMock.mockResolvedValue(noContent());
    const { result } = await renderHookWithClient(() => useDeclineGroupInvite(), client);
    await act(async () => {
      await result.current.mutateAsync('g2');
    });
    expect(call().url).toBe(`${BASE}/groups/g2/invites/decline`);
    expect(call().init.method).toBe('POST');
    expect(listIds(client)).toEqual(['g1']);
    expect(client.getQueryData(queryKeys.group('g2'))).toBeUndefined();
  });

  it('leaves caches alone when a mutation fails', async () => {
    const client = createTestQueryClient();
    seed(client);
    fetchMock.mockResolvedValue(jsonResponse(403, { message: 'Only the owner can do that' }));
    const { result } = await renderHookWithClient(() => useDeleteGroup(), client);
    await act(async () => {
      await result.current.mutateAsync('g1').catch(() => {});
    });
    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(listIds(client)).toEqual(['g1', 'g2']);
    expect(client.getQueryData(queryKeys.group('g1'))).toBeDefined();
  });
});

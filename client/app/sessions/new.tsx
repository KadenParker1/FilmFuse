import { useMemo, useState } from 'react';
import { View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MAX_INVITEES, useGroup } from '../../api/groups';
import { useCreateSession } from '../../api/sessions';
import { useLastSessionSetup, type SessionSetup } from '../../api/sessionSetup';
import { BackButton } from '../../components/BackButton';
import { FilterBanner } from '../../components/comfort/FilterBanner';
import { AvatarStack } from '../../components/groups/AvatarStack';
import { FriendPicker } from '../../components/groups/FriendPicker';
import { groupEmoji } from '../../components/groups/groupStyle';
import {
  EMPTY_FILTERS,
  SessionFiltersForm,
  StrategyPicker,
  toFiltersDTO,
  type SessionFilterState,
} from '../../components/sessions/SessionOptions';
import {
  Collapsible,
  MovieContextCard,
  SameCrewCard,
  SaveCrewAsGroup,
  filtersFromDTO,
  parseMemberIdsParam,
  parseMovieIdParam,
} from '../../components/sessions/SessionShortcuts';
import { DEFAULT_STRATEGY, strategyLabel, validateFromYear } from '../../components/sessions/sessionCopy';
import { Body, Button, Card, ErrorState, Heading, Screen, Skeleton, errorMessage } from '../../components/ui';
import { pluralize } from '../../lib/format';
import type { Strategy } from '../../types/api';

function GroupHeader({ groupId }: { groupId: string }) {
  const group = useGroup(groupId);
  if (group.isPending) {
    return (
      <View testID="session-group-loading" accessibilityLabel="Loading group">
        <Skeleton className="h-20 w-full" />
      </View>
    );
  }
  if (group.isError) {
    return (
      <ErrorState
        compact
        title="Couldn't load the group"
        error={group.error}
        onRetry={() => void group.refetch()}
        retrying={group.isFetching}
      />
    );
  }
  const active = group.data.members.filter((m) => m.status === 'ACTIVE');
  return (
    <Card className="gap-3">
      <Heading size="sm">
        {groupEmoji(group.data.emoji)} {group.data.name}
      </Heading>
      <View className="flex-row items-center gap-3">
        <AvatarStack users={active.map((m) => m.user)} total={group.data.memberCount} />
        <Body muted className="text-sm">
          {pluralize(group.data.memberCount, 'member')}
        </Body>
      </View>
      <FilterBanner filter={group.data.effectiveFilter} />
    </Card>
  );
}

type Params = { groupId?: string; memberIds?: string | string[]; movieId?: string };

/**
 * Start a session: from "Tonight" (pick friends) or from a group (its members, preselected).
 * `memberIds` (comma-separated) preselects friends ("Movie night with…"); `movieId` shows the movie
 * someone started from ("Watch with…") as context — sessions can't be seeded with a movie.
 * "Same crew again" fills in the last session's people, filters and strategy.
 */
export default function NewSessionScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<Params>();
  const groupId = typeof params.groupId === 'string' && params.groupId ? params.groupId : undefined;
  const presetIds = useMemo(
    () => (groupId ? [] : parseMemberIdsParam(params.memberIds, MAX_INVITEES)),
    [groupId, params.memberIds],
  );
  const movieId = parseMovieIdParam(params.movieId);
  const create = useCreateSession();
  const [memberIds, setMemberIds] = useState<string[]>(presetIds);
  const [filters, setFilters] = useState<SessionFilterState>(EMPTY_FILTERS);
  const [strategy, setStrategy] = useState<Strategy>(DEFAULT_STRATEGY);
  const [yearError, setYearError] = useState<string | null>(null);
  const [membersError, setMembersError] = useState<string | null>(null);
  const [usedSetup, setUsedSetup] = useState(false);
  // Opened again with other people (e.g. "Movie night with…" from another profile while this screen
  // is still mounted): take the new ones.
  const presetKey = presetIds.join(',');
  const [seenPresetKey, setSeenPresetKey] = useState(presetKey);
  if (seenPresetKey !== presetKey) {
    // Adjusting state while rendering (React's pattern for "reset when a prop changes").
    setSeenPresetKey(presetKey);
    if (presetIds.length) {
      setMemberIds(presetIds);
      setMembersError(null);
    }
  }
  // A link that already says who's watching doesn't need the last crew.
  const lastSetup = useLastSessionSetup(groupId, { enabled: presetIds.length === 0 });
  const setup = lastSetup.data ?? null;
  const offerSetup = !usedSetup && setup != null && (groupId != null || setup.members.length > 0);

  const applySetup = (s: SessionSetup) => {
    if (!groupId) setMemberIds(s.members.map((m) => m.id).slice(0, MAX_INVITEES));
    setFilters(filtersFromDTO(s.filters));
    setStrategy(s.strategy);
    setMembersError(null);
    setYearError(null);
    setUsedSetup(true);
  };

  const submit = () => {
    const { year, error } = validateFromYear(filters.fromYear);
    setYearError(error);
    const needFriends = !groupId && memberIds.length === 0;
    setMembersError(needFriends ? 'Pick at least one friend to watch with.' : null);
    if (error || needFriends) return;
    create.mutate(
      {
        ...(groupId ? { groupId } : { memberIds }),
        filters: toFiltersDTO(filters, year),
        strategy,
      },
      { onSuccess: (session) => router.replace(`/sessions/${session.id}`) },
    );
  };

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback={groupId ? `/groups/${groupId}` : '/groups'} />
        <Heading size="md">{groupId ? 'Start a session' : 'Tonight'}</Heading>
      </View>
      <View className="gap-6 pb-12">
        {movieId != null ? <MovieContextCard movieId={movieId} /> : null}
        {offerSetup ? <SameCrewCard setup={setup} includeMembers={!groupId} onUse={() => applySetup(setup)} /> : null}
        {usedSetup ? (
          <Body muted testID="same-crew-used" accessibilityLiveRegion="polite" className="text-sm">
            Filled in from your last session. Change anything you like.
          </Body>
        ) : null}
        {groupId ? (
          <GroupHeader groupId={groupId} />
        ) : (
          <View className="gap-3">
            <FriendPicker
              title="Who’s watching?"
              selected={memberIds}
              onChange={(ids) => {
                setMemberIds(ids);
                setMembersError(null);
              }}
              max={MAX_INVITEES}
            />
            <SaveCrewAsGroup memberIds={memberIds} />
          </View>
        )}
        {membersError ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {membersError}
          </Body>
        ) : null}
        <SessionFiltersForm
          value={filters}
          onChange={(f) => {
            setFilters(f);
            setYearError(null);
          }}
          yearError={yearError}
        />
        <Collapsible title="Advanced" summary={strategyLabel(strategy)}>
          <StrategyPicker value={strategy} onChange={setStrategy} />
        </Collapsible>
        {create.isError ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {errorMessage(create.error)}
          </Body>
        ) : null}
        <Button title="Find movies" loading={create.isPending} onPress={submit} />
      </View>
    </Screen>
  );
}

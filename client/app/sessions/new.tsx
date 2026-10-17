import { useState } from 'react';
import { View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { MAX_INVITEES, useGroup } from '../../api/groups';
import { useCreateSession } from '../../api/sessions';
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
import { DEFAULT_STRATEGY, validateFromYear } from '../../components/sessions/sessionCopy';
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

/** Start a session: from "Tonight" (pick friends) or from a group (its members, preselected). */
export default function NewSessionScreen() {
  const router = useRouter();
  const { groupId } = useLocalSearchParams<{ groupId?: string }>();
  const create = useCreateSession();
  const [memberIds, setMemberIds] = useState<string[]>([]);
  const [filters, setFilters] = useState<SessionFilterState>(EMPTY_FILTERS);
  const [strategy, setStrategy] = useState<Strategy>(DEFAULT_STRATEGY);
  const [yearError, setYearError] = useState<string | null>(null);
  const [membersError, setMembersError] = useState<string | null>(null);

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
        {groupId ? (
          <GroupHeader groupId={groupId} />
        ) : (
          <FriendPicker
            title="Who’s watching?"
            selected={memberIds}
            onChange={(ids) => {
              setMemberIds(ids);
              setMembersError(null);
            }}
            max={MAX_INVITEES}
          />
        )}
        {membersError ? (
          <Body accessibilityRole="alert" className="text-sm text-velvet">
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
        <StrategyPicker value={strategy} onChange={setStrategy} />
        {create.isError ? (
          <Body accessibilityRole="alert" className="text-sm text-velvet">
            {errorMessage(create.error)}
          </Body>
        ) : null}
        <Button title="Find movies" loading={create.isPending} onPress={submit} />
      </View>
    </Screen>
  );
}

import { useState } from 'react';
import { Text, View } from 'react-native';
import { useLocalSearchParams, useRouter, type Href } from 'expo-router';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSessionLive } from '../../api/sessionLive';
import {
  useCastVote,
  useClearVote,
  useEndSession,
  useJoinSession,
  usePickMovie,
  useRefreshSession,
  useSession,
} from '../../api/sessions';
import { useAuth } from '../../auth/useAuth';
import { BackButton } from '../../components/BackButton';
import { FilterBanner } from '../../components/comfort/FilterBanner';
import { AddPeopleSheet } from '../../components/sessions/AddPeopleSheet';
import { LiveIndicator } from '../../components/sessions/LiveIndicator';
import { SessionMembersRow } from '../../components/sessions/SessionMembersRow';
import { SessionResultCard } from '../../components/sessions/SessionResultCard';
import { ShareSessionButton } from '../../components/sessions/ShareSessionButton';
import { TallyPanel } from '../../components/sessions/TallyPanel';
import { STATUS_LABELS, orderResults, strategyLabel } from '../../components/sessions/sessionCopy';
import { computeTally, timeLeft } from '../../components/sessions/tally';
import {
  Body,
  Button,
  Card,
  ConfirmSheet,
  EmptyState,
  ErrorState,
  Heading,
  Screen,
  Skeleton,
  errorMessage,
} from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import { pluralize } from '../../lib/format';
import type { LiveMode } from '../../lib/realtime';
import { useTheme } from '../../theme/useTheme';
import type { MovieSummaryDTO, SessionDTO, SessionResultDTO } from '../../types/api';

function DecidedHero({ movie }: { movie: MovieSummaryDTO }) {
  const router = useRouter();
  const { colors } = useTheme();
  return (
    <Card testID="decided-hero" className="items-center gap-4 py-6">
      <Text className="font-body-semibold text-sm uppercase tracking-widest text-accent-ink">Tonight’s pick</Text>
      <View className="aspect-[2/3] w-44 overflow-hidden rounded-2xl border-2 border-accent bg-raised">
        {movie.posterUrl ? (
          <Image
            testID="decided-poster"
            source={{ uri: movie.posterUrl }}
            style={{ width: '100%', height: '100%' }}
            contentFit="cover"
          />
        ) : null}
      </View>
      <Heading size="md" className="text-center">
        {movie.title}
      </Heading>
      {movie.releaseYear ? <Body muted>{movie.releaseYear}</Body> : null}
      <View testID="rate-after-watching" className="w-full gap-2 rounded-2xl bg-raised p-3">
        <View className="flex-row items-start gap-2">
          <Ionicons name="star-outline" size={18} color={colors.accent} />
          <Body className="flex-1 text-sm">
            Rate it after watching: it’ll wait for you under “Rate what you watched” on the Groups tab, and a quick
            1–10 helps everyone’s next picks.
          </Body>
        </View>
        <Button
          size="sm"
          variant="ghost"
          title="Go to Rate what you watched"
          onPress={() => router.push('/groups')}
        />
      </View>
      <Button title="Movie details" variant="secondary" onPress={() => router.push(`/movie/${movie.id}`)} />
    </Card>
  );
}

function OpenResults({ session, myId }: { session: SessionDTO; myId: string | undefined }) {
  const router = useRouter();
  const vote = useCastVote();
  const clear = useClearVote();
  const refresh = useRefreshSession();
  const pick = usePickMovie();
  const [vetoing, setVetoing] = useState<SessionResultDTO | null>(null);
  const [picking, setPicking] = useState<SessionResultDTO | null>(null);
  // Display order stays the ML rank order; the tally only highlights.
  const results = orderResults(session.results);
  const tally = computeTally(session.results, session.memberCount, session.votedCount);
  const tiedIds = new Set(tally.isTie ? tally.tied.map((r) => r.movie.id) : []);
  const voteError = vote.isError ? vote.error : clear.isError ? clear.error : null;
  // Joined after these picks were ranked: none of them carries the caller's fit.
  const joinedLate =
    !!myId && results.length > 0 && session.members.some((m) => m.user.id === myId) &&
    results.every((r) => r.memberScores[myId] === undefined);

  return (
    <View className="gap-4">
      {results.length ? <TallyPanel tally={tally} onPickLeader={setPicking} /> : null}
      {session.hiddenResultCount > 0 ? (
        <Card testID="hidden-results" className="gap-2">
          <Body className="text-sm">
            {pluralize(session.hiddenResultCount, 'pick')} no longer {session.hiddenResultCount === 1 ? 'fits' : 'fit'}{' '}
            everyone’s comfort settings, so {session.hiddenResultCount === 1 ? 'it’s' : 'they’re'} hidden.
          </Body>
          <Button size="sm" variant="ghost" title="Get more options" onPress={() => refresh.mutate(session.id)} />
        </Card>
      ) : null}
      {joinedLate ? (
        <Body muted testID="joined-late" className="text-sm">
          You joined after these picks were made. “More options” adds picks that include your taste.
        </Body>
      ) : null}
      {voteError ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {errorMessage(voteError)}
        </Body>
      ) : null}
      {results.length === 0 ? (
        <EmptyState
          icon="film-outline"
          title="Nothing fits yet"
          message="No movies match everyone’s filters. Try more options, or loosen the session filters."
        />
      ) : (
        results.map((r) => (
          <SessionResultCard
            key={r.movie.id}
            result={r}
            members={session.members}
            leader={!tally.isTie && tally.leader?.movie.id === r.movie.id}
            tied={tiedIds.has(r.movie.id)}
            everyoneLikes={tally.everyoneLikes.has(r.movie.id)}
            onOpenMovie={() => router.push(`/movie/${r.movie.id}`)}
            onVote={(v) =>
              v === 'VETO'
                ? setVetoing(r)
                : vote.mutate({ sessionId: session.id, movieId: r.movie.id, vote: v })
            }
            onClearVote={() => clear.mutate({ sessionId: session.id, movieId: r.movie.id })}
            onPick={() => setPicking(r)}
          />
        ))
      )}
      {refresh.isError ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {errorMessage(refresh.error)}
        </Body>
      ) : null}
      <Button
        title="More options"
        variant="secondary"
        loading={refresh.isPending}
        onPress={() => refresh.mutate(session.id)}
      />

      <ConfirmSheet
        visible={vetoing !== null}
        title={`Veto ${vetoing?.movie.title ?? ''}?`}
        message="A veto takes it off the table for the whole group. Nobody sees who vetoed it."
        confirmLabel="Veto"
        danger
        onConfirm={() => {
          if (vetoing) vote.mutate({ sessionId: session.id, movieId: vetoing.movie.id, vote: 'VETO' });
          setVetoing(null);
        }}
        onClose={() => setVetoing(null)}
      />
      <ConfirmSheet
        visible={picking !== null}
        title={`Watch ${picking?.movie.title ?? ''}?`}
        message="This ends voting for everyone and adds it to everyone’s watch history."
        confirmLabel="Pick this"
        loading={pick.isPending}
        error={pick.isError ? pick.error : undefined}
        onConfirm={() => {
          if (picking)
            pick.mutate(
              { sessionId: session.id, movieId: picking.movie.id },
              { onSuccess: () => setPicking(null) },
            );
        }}
        onClose={() => {
          pick.reset();
          setPicking(null);
        }}
      />
    </View>
  );
}

/** Share for everyone; Add people for members of an open session; End for its creator. */
function SessionActions({ session, myId }: { session: SessionDTO; myId: string | undefined }) {
  const end = useEndSession();
  const [adding, setAdding] = useState(false);
  const [ending, setEnding] = useState(false);
  const open = session.status === 'OPEN';
  const isCreator = !!myId && session.createdBy?.id === myId;
  return (
    <View className="flex-row flex-wrap items-start gap-2">
      <ShareSessionButton url={session.shareUrl} />
      {open ? <Button size="sm" variant="secondary" title="Add people" onPress={() => setAdding(true)} /> : null}
      {open && isCreator ? (
        <Button size="sm" variant="ghost" title="End session" onPress={() => setEnding(true)} />
      ) : null}
      {open ? <AddPeopleSheet session={session} visible={adding} onClose={() => setAdding(false)} /> : null}
      <ConfirmSheet
        visible={ending}
        title="End this session?"
        message="Voting stops for everyone and nothing gets picked. It moves to your history."
        confirmLabel="End session"
        danger
        loading={end.isPending}
        error={end.isError ? end.error : undefined}
        onConfirm={() => end.mutate(session.id, { onSuccess: () => setEnding(false) })}
        onClose={() => {
          end.reset();
          setEnding(false);
        }}
      />
    </View>
  );
}

function SessionView({ session, mode, myId }: { session: SessionDTO; mode: LiveMode; myId: string | undefined }) {
  const router = useRouter();
  const open = session.status === 'OPEN';
  return (
    <View className="gap-5 pb-12">
      <View className="gap-2">
        <Heading size="md">{session.groupName ?? 'Tonight'}</Heading>
        <Body muted testID="session-meta" className="text-sm">
          {session.endedEarly ? 'Ended early' : STATUS_LABELS[session.status]} · {strategyLabel(session.strategy)}
          {open ? ` · ${timeLeft(session.closesAt)}` : ''}
        </Body>
      </View>
      {/* Live pill and the filter pill side by side, as in the Technicolor session header. */}
      <View className="flex-row flex-wrap items-center gap-2">
        {open ? <LiveIndicator mode={mode} /> : null}
        <FilterBanner filter={session.effectiveFilter} />
      </View>
      <SessionActions session={session} myId={myId} />
      <SessionMembersRow
        members={session.members}
        memberCount={session.memberCount}
        votedCount={session.votedCount}
        showWaiting={open}
      />

      {session.status === 'DECIDED' && session.pickedMovie ? <DecidedHero movie={session.pickedMovie} /> : null}

      {session.status === 'EXPIRED' ? (
        <View className="gap-4">
          <Card testID="expired-banner">
            <Body>
              {session.endedEarly
                ? 'This session was ended early — nothing was picked.'
                : 'This session expired — voting closes 24 hours after it starts.'}
            </Body>
          </Card>
          {orderResults(session.results).map((r) => (
            <SessionResultCard
              key={r.movie.id}
              readOnly
              result={r}
              members={session.members}
              onOpenMovie={() => router.push(`/movie/${r.movie.id}`)}
              onVote={() => {}}
              onClearVote={() => {}}
              onPick={() => {}}
            />
          ))}
        </View>
      ) : null}

      {open ? <OpenResults session={session} myId={myId} /> : null}
    </View>
  );
}

/** A group member who isn't in this open session yet (403 from the server): offer to join. */
function JoinPrompt({ sessionId, message }: { sessionId: string; message: string }) {
  const join = useJoinSession();
  return (
    <Card testID="join-session" className="gap-3">
      <Body>{message}</Body>
      {join.isError ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {errorMessage(join.error)}
        </Body>
      ) : null}
      <Button title="Join session" loading={join.isPending} onPress={() => join.mutate(sessionId)} />
    </Card>
  );
}

/** A live recommendation session: ranked picks with the Fuse meter, live tally, voting, veto, refresh and pick. */
export default function SessionScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const { user } = useAuth();
  const session = useSession(id);
  const { mode } = useSessionLive(id);
  const groupId = session.data?.groupId;
  const fallback: Href = groupId ? `/groups/${groupId}` : '/groups';

  let content;
  if (session.isPending) {
    content = (
      <View testID="session-loading" className="gap-4">
        <Skeleton className="h-8 w-1/2" />
        <Skeleton className="h-10 w-full" />
        <Skeleton className="h-40 w-full" />
        <Skeleton className="h-40 w-full" />
      </View>
    );
  } else if (session.isError) {
    const status = session.error instanceof ApiError ? session.error.status : null;
    if (status === 403 && id) {
      content = <JoinPrompt sessionId={id} message={errorMessage(session.error)} />;
    } else if (status === 404) {
      content = (
        <EmptyState
          icon="film-outline"
          title="Session not found"
          message="Only people in a session can see it. Ask someone in it to add you."
        />
      );
    } else {
      content = (
        <ErrorState
          title="Couldn't load this session"
          error={session.error}
          onRetry={() => void session.refetch()}
          retrying={session.isFetching}
        />
      );
    }
  } else {
    content = <SessionView session={session.data} mode={mode} myId={user?.id} />;
  }

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback={fallback} />
      </View>
      {content}
    </Screen>
  );
}

import { useState } from 'react';
import { Text, View } from 'react-native';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { Image } from 'expo-image';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useSessionLive } from '../../api/sessionLive';
import { useCastVote, useClearVote, usePickMovie, useRefreshSession, useSession } from '../../api/sessions';
import { BackButton } from '../../components/BackButton';
import { FilterBanner } from '../../components/comfort/FilterBanner';
import { LiveIndicator } from '../../components/sessions/LiveIndicator';
import { SessionMembersRow } from '../../components/sessions/SessionMembersRow';
import { SessionResultCard } from '../../components/sessions/SessionResultCard';
import { STATUS_LABELS, orderResults, strategyLabel } from '../../components/sessions/sessionCopy';
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
import type { LiveMode } from '../../lib/realtime';
import { useTheme } from '../../theme/useTheme';
import type { MovieSummaryDTO, SessionDTO, SessionResultDTO } from '../../types/api';

function DecidedHero({ movie }: { movie: MovieSummaryDTO }) {
  const router = useRouter();
  const { colors } = useTheme();
  return (
    <Card testID="decided-hero" className="items-center gap-4 py-6">
      <Text className="font-body-semibold text-sm uppercase tracking-widest text-gold">Tonight’s pick</Text>
      <View className="aspect-[2/3] w-44 overflow-hidden rounded-2xl border-2 border-gold bg-surface-raised">
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
      <View testID="rate-after-watching" className="w-full gap-2 rounded-xl border border-border bg-surface-raised p-3">
        <View className="flex-row items-start gap-2">
          <Ionicons name="star-outline" size={18} color={colors.gold} />
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

function OpenResults({ session }: { session: SessionDTO }) {
  const router = useRouter();
  const vote = useCastVote();
  const clear = useClearVote();
  const refresh = useRefreshSession();
  const pick = usePickMovie();
  const [vetoing, setVetoing] = useState<SessionResultDTO | null>(null);
  const [picking, setPicking] = useState<SessionResultDTO | null>(null);
  const results = orderResults(session.results);
  const voteError = vote.isError ? vote.error : clear.isError ? clear.error : null;

  return (
    <View className="gap-4">
      {voteError ? (
        <Body accessibilityRole="alert" className="text-sm text-velvet">
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
        <Body accessibilityRole="alert" className="text-sm text-velvet">
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

function SessionView({ session, mode }: { session: SessionDTO; mode: LiveMode }) {
  const router = useRouter();
  return (
    <View className="gap-5 pb-12">
      <View className="gap-2">
        <View className="flex-row flex-wrap items-center justify-between gap-2">
          <Heading size="md">{session.groupName ?? 'Tonight'}</Heading>
          {session.status === 'OPEN' ? <LiveIndicator mode={mode} /> : null}
        </View>
        <Body muted testID="session-meta" className="text-sm">
          {STATUS_LABELS[session.status]} · {strategyLabel(session.strategy)}
        </Body>
      </View>
      <FilterBanner filter={session.effectiveFilter} />
      <SessionMembersRow members={session.members} />

      {session.status === 'DECIDED' && session.pickedMovie ? <DecidedHero movie={session.pickedMovie} /> : null}

      {session.status === 'EXPIRED' ? (
        <View className="gap-4">
          <Card testID="expired-banner">
            <Body>This session expired — voting closes 24 hours after it starts.</Body>
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

      {session.status === 'OPEN' ? <OpenResults session={session} /> : null}
    </View>
  );
}

/** A live recommendation session: ranked picks with the Fuse meter, voting, veto, refresh and pick. */
export default function SessionScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const session = useSession(id);
  const { mode } = useSessionLive(id);
  const fallback = session.data?.groupId ? `/groups/${session.data.groupId}` : '/groups';

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
    content =
      session.error instanceof ApiError && session.error.status === 404 ? (
        <EmptyState icon="film-outline" title="Session not found" message="Only people in a session can see it." />
      ) : (
        <ErrorState
          title="Couldn't load this session"
          error={session.error}
          onRetry={() => void session.refetch()}
          retrying={session.isFetching}
        />
      );
  } else {
    content = <SessionView session={session.data} mode={mode} />;
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

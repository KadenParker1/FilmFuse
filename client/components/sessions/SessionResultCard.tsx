import type { ComponentProps } from 'react';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTheme } from '../../theme/useTheme';
import type { SessionMemberDTO, SessionResultDTO, Vote } from '../../types/api';
import { Body, PosterCard } from '../ui';
import { FuseMeter } from './FuseMeter';

type Props = {
  result: SessionResultDTO;
  members: SessionMemberDTO[];
  /** Read-only (decided / expired): no vote or pick controls. */
  readOnly?: boolean;
  onVote: (vote: Vote) => void;
  /** Tapping your current vote again takes it back. */
  onClearVote: () => void;
  onPick: () => void;
  onOpenMovie?: () => void;
};

type IconName = ComponentProps<typeof Ionicons>['name'];

function VoteButton({
  label,
  icon,
  count,
  selected,
  danger,
  disabled,
  onPress,
}: {
  label: string;
  icon: IconName;
  count?: number;
  selected: boolean;
  danger?: boolean;
  disabled?: boolean;
  onPress: () => void;
}) {
  const { colors } = useTheme();
  const tint = selected ? (danger ? colors.onDanger : colors.onAccent) : colors.text;
  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={count != null ? `${label}, ${count}` : label}
      accessibilityState={{ selected, disabled: !!disabled }}
      disabled={disabled}
      onPress={onPress}
      className={`min-h-9 flex-row items-center gap-1.5 rounded-[10px] border px-3 py-1.5 active:opacity-80 ${
        selected ? (danger ? 'border-danger bg-danger' : 'border-accent bg-accent') : 'border-border bg-raised'
      } ${disabled ? 'opacity-50' : ''}`}
    >
      <Ionicons name={icon} size={16} color={tint} />
      <Text className={`font-body-bold text-sm ${selected ? (danger ? 'text-on-danger' : 'text-on-accent') : 'text-text'}`}>
        {count ?? label}
      </Text>
    </Pressable>
  );
}

/** One ranked movie: poster, rating, Fuse meter, why it was picked, votes and "Pick this". */
export function SessionResultCard({ result, members, readOnly = false, onVote, onClearVote, onPick, onOpenMovie }: Props) {
  const { colors } = useTheme();
  const { movie, votes, myVote } = result;
  const vetoed = votes.vetoed;
  const press = (vote: Vote) => (myVote === vote ? onClearVote() : onVote(vote));

  return (
    <View
      testID={`result-${movie.id}`}
      className={`flex-row gap-4 rounded-[18px] border border-border bg-surface p-3 ${vetoed ? 'opacity-50' : ''}`}
    >
      <View className="w-24">
        <PosterCard fill title={movie.title} posterUrl={movie.posterUrl} certification={result.certification} onPress={onOpenMovie} />
      </View>
      <View className="flex-1 gap-2">
        <View className="flex-row items-start gap-2">
          <Text className="flex-1 font-display-bold text-lg tracking-tight text-text" numberOfLines={2}>
            {vetoed ? '' : `${result.rank}. `}
            {movie.title}
          </Text>
        </View>
        {movie.releaseYear ? <Body muted className="text-xs">{movie.releaseYear}</Body> : null}
        {vetoed ? (
          <Text testID={`vetoed-${movie.id}`} className="font-body-semibold text-sm text-danger">
            Vetoed
          </Text>
        ) : null}
        <FuseMeter members={members} scores={result.memberScores} />
        <Body muted className="text-sm">
          {result.explanation}
        </Body>
        <View className="flex-row flex-wrap items-center gap-2">
          <VoteButton
            label="Thumbs up"
            icon="thumbs-up"
            count={votes.up}
            selected={myVote === 'UP'}
            disabled={readOnly}
            onPress={() => press('UP')}
          />
          <VoteButton
            label="Thumbs down"
            icon="thumbs-down"
            count={votes.down}
            selected={myVote === 'DOWN'}
            disabled={readOnly}
            onPress={() => press('DOWN')}
          />
          <VoteButton
            label="Veto"
            icon="hand-left"
            selected={myVote === 'VETO'}
            danger
            disabled={readOnly}
            onPress={() => press('VETO')}
          />
        </View>
        {!readOnly && !vetoed ? (
          <Pressable
            accessibilityRole="button"
            accessibilityLabel={`Pick ${movie.title}`}
            onPress={onPick}
            className="min-h-9 flex-row items-center gap-1.5 self-start rounded-[10px] bg-accent2 px-3.5 py-1.5 active:opacity-80"
          >
            <Ionicons name="checkmark-circle" size={16} color={colors.onAccent2} />
            <Text className="font-body-bold text-sm text-on-accent2">Pick this</Text>
          </Pressable>
        ) : null}
      </View>
    </View>
  );
}

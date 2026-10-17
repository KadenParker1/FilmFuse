import { Text, View } from 'react-native';
import type { RecommendationDTO } from '../../types/api';
import { CertificationBadge } from '../sessions/CertificationBadge';
import { Button, PosterCard } from '../ui';

type Props = {
  rec: RecommendationDTO;
  onOpen: () => void;
  onSeen: () => void;
  onDismiss: () => void;
};

/** One "For you" pick: poster, rating badge, why it was picked, and Seen it / Not interested. */
export function RecommendationCard({ rec, onOpen, onSeen, onDismiss }: Props) {
  const { movie } = rec;
  return (
    <View testID={`rec-${movie.id}`} className="w-40 gap-2">
      <PosterCard
        fill
        title={movie.title}
        posterUrl={movie.posterUrl}
        subtitle={movie.releaseYear != null ? String(movie.releaseYear) : undefined}
        onPress={onOpen}
      />
      <CertificationBadge certification={rec.certification} />
      <Text testID={`rec-explanation-${movie.id}`} className="font-body text-xs text-text-muted" numberOfLines={3}>
        {rec.explanation}
      </Text>
      <Button
        size="sm"
        title="Seen it"
        variant="secondary"
        accessibilityLabel={`Seen ${movie.title}, rate it`}
        onPress={onSeen}
      />
      <Button
        size="sm"
        title="Not interested"
        variant="ghost"
        accessibilityLabel={`Not interested in ${movie.title}`}
        onPress={onDismiss}
      />
    </View>
  );
}

import { ActivityIndicator, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { pluralize } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { TasteProfileDTO } from '../../types/api';
import { Body, Card, Label } from '../ui';
import { dimensionValue, formatScore, STATUS_LABELS } from './tasteCopy';

/** The taste persona ("Cozy Sci-Fi Romantic"), its status and the top genres with their scores. */
export function PersonaCard({ profile }: { profile: TasteProfileDTO }) {
  const { colors } = useTheme();
  const rebuilding = profile.status === 'REBUILDING';
  return (
    <Card raised testID="persona-card" className="gap-4">
      <View className="flex-row items-center justify-between gap-3">
        <Label>Your taste persona</Label>
        <View
          testID="taste-status"
          accessibilityLabel={`Taste profile ${STATUS_LABELS[profile.status].toLowerCase()}`}
          className="flex-row items-center gap-1.5 rounded-full border border-border px-2.5 py-0.5"
        >
          {rebuilding ? <ActivityIndicator size="small" color={colors.gold} /> : null}
          <Text className="font-body-medium text-xs text-text-muted">{STATUS_LABELS[profile.status]}</Text>
        </View>
      </View>
      <View className="flex-row items-center gap-3">
        <View className="h-12 w-12 items-center justify-center rounded-full bg-gold">
          <Ionicons name="sparkles" size={22} color={colors.onGold} />
        </View>
        <Text testID="persona" className="flex-1 font-display-bold text-2xl text-text">
          {profile.persona ?? 'Your taste is taking shape'}
        </Text>
      </View>
      {profile.topGenres.length > 0 ? (
        <View testID="top-genres" className="gap-2">
          {profile.topGenres.map((g) => {
            const value = dimensionValue(g);
            return (
              <View key={g.key} className="flex-row items-center gap-3">
                <Text className="w-28 font-body-medium text-sm text-text" numberOfLines={1}>
                  {g.label}
                </Text>
                <View className="h-2 flex-1 overflow-hidden rounded-full bg-border">
                  <View
                    className="h-2 rounded-full bg-gold"
                    style={{ width: `${value != null ? (value / 10) * 100 : 0}%` }}
                  />
                </View>
                <Text className="w-8 text-right font-body-semibold text-sm text-gold">
                  {value != null ? formatScore(value) : '–'}
                </Text>
              </View>
            );
          })}
        </View>
      ) : null}
      <Body muted className="text-sm">
        {profile.ratingsCount > 0
          ? `Based on ${pluralize(profile.ratingsCount, 'rating')} and what you told us.`
          : 'Based on what you told us. Rate movies to sharpen it.'}
      </Body>
    </Card>
  );
}

import { ActivityIndicator, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { pluralize } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { TasteProfileDTO } from '../../types/api';
import { Body, Card, Label } from '../ui';
import { dimensionValue, formatScore, STATUS_LABELS, topGenresNote } from './tasteCopy';

/**
 * The taste persona ("Cozy Sci-Fi Romantic"), its status and the top genres with their scores. The
 * server sends only genres above neutral, so the list may be short or empty; a friendly line says why.
 */
export function PersonaCard({ profile }: { profile: TasteProfileDTO }) {
  const { colors, fuse } = useTheme();
  const rebuilding = profile.status === 'REBUILDING';
  const note = topGenresNote(profile.topGenres.length);
  return (
    <Card raised testID="persona-card" className="gap-4">
      <View className="flex-row items-center justify-between gap-3">
        <Label>Your taste persona</Label>
        <View
          testID="taste-status"
          accessibilityLabel={`Taste profile ${STATUS_LABELS[profile.status].toLowerCase()}`}
          className={`flex-row items-center gap-1.5 rounded-full px-2.5 py-0.5 ${
            profile.status === 'FRESH' ? 'bg-success-soft' : 'bg-surface'
          }`}
        >
          {rebuilding ? <ActivityIndicator size="small" color={colors.accent} /> : null}
          <Text
            className={`font-body-semibold text-xs ${profile.status === 'FRESH' ? 'text-success' : 'text-muted'}`}
          >
            {STATUS_LABELS[profile.status]}
          </Text>
        </View>
      </View>
      <View className="flex-row items-center gap-3">
        <View className="h-12 w-12 items-center justify-center rounded-2xl bg-accent">
          <Ionicons name="sparkles" size={22} color={colors.onAccent} />
        </View>
        <Text testID="persona" className="flex-1 font-display-bold text-2xl text-text">
          {profile.persona ?? 'Your taste is taking shape'}
        </Text>
      </View>
      {profile.topGenres.length > 0 ? (
        <View testID="top-genres" className="gap-2.5">
          {profile.topGenres.map((g, i) => {
            const value = dimensionValue(g);
            return (
              <View key={g.key} className="flex-row items-center gap-3">
                <Text className="w-28 font-body-medium text-sm text-text" numberOfLines={1}>
                  {g.label}
                </Text>
                <View className="h-2.5 flex-1 overflow-hidden rounded-full bg-surface">
                  <View
                    className="h-2.5 rounded-full"
                    style={{
                      width: `${value != null ? (value / 10) * 100 : 0}%`,
                      backgroundColor: fuse[i % fuse.length],
                    }}
                  />
                </View>
                <Text className="w-8 text-right font-body-bold text-sm text-accent-ink">
                  {value != null ? formatScore(value) : '–'}
                </Text>
              </View>
            );
          })}
        </View>
      ) : null}
      {note ? (
        <Body muted testID="top-genres-note" className="text-sm">
          {note}
        </Body>
      ) : null}
      <Body muted className="text-sm">
        {profile.ratingsCount > 0
          ? `Based on ${pluralize(profile.ratingsCount, 'rating')} and what you told us.`
          : 'Based on what you told us. Rate movies to sharpen it.'}
      </Body>
    </Card>
  );
}

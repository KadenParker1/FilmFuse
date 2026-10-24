import { Text, View } from 'react-native';
import { formatCount, formatMb, percent, shortDay } from '../../lib/admin';
import type { Palette } from '../../theme/tokens';
import { useTheme } from '../../theme/useTheme';
import type { DailyCountDTO } from '../../types/api';

const BAR_AREA_HEIGHT = 120;

/**
 * Daily counts as a bar chart from plain views (no chart dependency). Screen readers get one summary
 * (total, busiest day, today) instead of 30 bars; sighted users get the peak value and the date range.
 */
export function DailyBars({ data, label }: { data: DailyCountDTO[]; label: string }) {
  const max = data.reduce((m, d) => Math.max(m, d.count), 0);
  const total = data.reduce((sum, d) => sum + d.count, 0);
  const peak = data.find((d) => d.count === max && max > 0);
  const first = data[0];
  const last = data[data.length - 1];
  const summary = `${label}: ${formatCount(total)} in ${data.length} days${
    peak ? `, busiest ${shortDay(peak.date)} with ${formatCount(peak.count)}` : ''
  }${last ? `, today ${formatCount(last.count)}` : ''}.`;

  return (
    <View accessible accessibilityRole="image" accessibilityLabel={summary} testID="daily-bars" className="gap-2">
      <View className="flex-row items-baseline justify-between">
        <Text className="font-body-semibold text-sm text-muted">{label}</Text>
        <Text className="font-body text-xs text-muted">peak {formatCount(max)}</Text>
      </View>
      <View className="flex-row items-end gap-[2px]" style={{ height: BAR_AREA_HEIGHT }}>
        {data.map((day) => {
          const height = max > 0 ? Math.max(day.count > 0 ? 3 : 1, Math.round((day.count / max) * BAR_AREA_HEIGHT)) : 1;
          return (
            <View
              key={day.date}
              testID={`bar-${day.date}`}
              className={`flex-1 rounded-t-sm ${day.count > 0 ? 'bg-accent' : 'bg-border'}`}
              style={{ height }}
            />
          );
        })}
      </View>
      {first && last ? (
        <View className="flex-row justify-between">
          <Text className="font-body text-xs text-muted">{shortDay(first.date)}</Text>
          <Text className="font-body text-xs text-muted">{shortDay(last.date)}</Text>
        </View>
      ) : null}
    </View>
  );
}

export type Share = { label: string; value: number; color: keyof Palette };

/**
 * A single stacked bar of parts of a whole, with a legend that carries the numbers (colour is never
 * the only cue).
 */
export function ShareBar({ shares, label }: { shares: Share[]; label: string }) {
  const { colors } = useTheme();
  const total = shares.reduce((sum, s) => sum + s.value, 0);
  return (
    <View className="gap-3" testID="share-bar">
      <View
        accessible
        accessibilityRole="image"
        accessibilityLabel={`${label}: ${shares.map((s) => `${s.label} ${formatCount(s.value)}`).join(', ')}.`}
        className="h-3 flex-row overflow-hidden rounded-full bg-raised"
      >
        {total > 0
          ? shares.map((s) =>
              s.value > 0 ? (
                <View key={s.label} testID={`share-${s.label}`} style={{ width: `${(s.value / total) * 100}%`, backgroundColor: colors[s.color] }} />
              ) : null,
            )
          : null}
      </View>
      <View className="flex-row flex-wrap gap-x-4 gap-y-1" importantForAccessibility="no-hide-descendants">
        {shares.map((s) => (
          <View key={s.label} className="flex-row items-center gap-1.5">
            <View className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: colors[s.color] }} />
            <Text className="font-body text-sm text-text">
              {s.label} <Text className="text-muted">{formatCount(s.value)} · {percent(s.value, total)}</Text>
            </Text>
          </View>
        ))}
      </View>
    </View>
  );
}

/**
 * The database size against the catalog jobs' size guard and the plan's limit. Turns tangerine past
 * 80% of the guard and red past the guard.
 */
export function SizeMeter({ bytes, guardMb, planMb }: { bytes: number; guardMb: number; planMb: number }) {
  const usedMb = bytes / (1024 * 1024);
  const scale = Math.max(planMb, guardMb, usedMb, 1);
  const tone = usedMb >= guardMb ? 'bg-danger' : usedMb >= guardMb * 0.8 ? 'bg-accent2' : 'bg-success';
  const summary = `Database ${formatMb(bytes)} of a ${guardMb} MB guard and a ${planMb} MB plan limit.`;
  return (
    <View accessible accessibilityRole="progressbar" accessibilityLabel={summary} testID="size-meter" className="gap-2">
      <View className="h-4 overflow-hidden rounded-full bg-raised">
        <View testID="size-meter-fill" className={`h-4 ${tone}`} style={{ width: `${Math.min(100, (usedMb / scale) * 100)}%` }} />
        <View
          testID="size-meter-guard"
          className="absolute bottom-0 top-0 w-[2px] bg-text"
          style={{ left: `${Math.min(100, (guardMb / scale) * 100)}%` }}
        />
      </View>
      <View className="flex-row flex-wrap justify-between gap-2">
        <Text className="font-body-semibold text-sm text-text">{formatMb(bytes)} used</Text>
        <Text className="font-body text-sm text-muted">
          guard {guardMb} MB · plan {planMb} MB
        </Text>
      </View>
    </View>
  );
}

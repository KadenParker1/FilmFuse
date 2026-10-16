import { useCallback, useState, type ReactElement } from 'react';
import { ActivityIndicator, FlatList, View, type LayoutChangeEvent } from 'react-native';
import { useTheme } from '../../theme/useTheme';
import { PosterCard } from './PosterCard';
import { Skeleton } from './Skeleton';

export type PosterItem = {
  id: number;
  title: string;
  posterUrl: string | null;
  subtitle?: string;
  score?: number | null;
};

/** Target poster width; columns = how many fit, never fewer than 2 (phones) or more than 6. */
const TARGET_CELL = 160;
const GAP = 16;

export function posterColumns(width: number): number {
  if (!Number.isFinite(width) || width <= 0) return 2;
  return Math.max(2, Math.min(6, Math.floor((width + GAP) / (TARGET_CELL + GAP))));
}

type Props = {
  items: PosterItem[];
  onPressItem?: (item: PosterItem) => void;
  /** First page loading: show skeleton cells instead of items. */
  loading?: boolean;
  /** A further page is loading: spinner in the footer. */
  loadingMore?: boolean;
  onEndReached?: () => void;
  header?: ReactElement;
  footer?: ReactElement;
  /** Shown instead of items when not loading and there are none. */
  empty?: ReactElement;
  testID?: string;
};

/** Responsive, infinitely scrolling grid of posters. Columns follow the measured width. */
export function PosterGrid({
  items,
  onPressItem,
  loading = false,
  loadingMore = false,
  onEndReached,
  header,
  footer,
  empty,
  testID = 'poster-grid',
}: Props) {
  const { colors } = useTheme();
  const [width, setWidth] = useState(0);
  const columns = posterColumns(width);
  const onLayout = useCallback((e: LayoutChangeEvent) => setWidth(e.nativeEvent.layout.width), []);

  const skeletons: PosterItem[] = Array.from({ length: columns * 3 }, (_, i) => ({
    id: -1 - i,
    title: '',
    posterUrl: null,
  }));
  const data = loading ? skeletons : items;

  return (
    <View className="flex-1" onLayout={onLayout}>
      <FlatList
        // FlatList can't change numColumns on the fly; remount when it changes.
        key={`cols-${columns}`}
        testID={testID}
        data={data}
        numColumns={columns}
        keyExtractor={(item) => String(item.id)}
        columnWrapperStyle={{ gap: GAP }}
        contentContainerStyle={{ gap: 20, paddingBottom: 120 }}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
        onEndReachedThreshold={0.6}
        onEndReached={loading ? undefined : onEndReached}
        ListHeaderComponent={header}
        ListEmptyComponent={loading ? undefined : empty}
        ListFooterComponent={
          <View className="gap-4">
            {loadingMore ? (
              <ActivityIndicator testID="poster-grid-loading-more" color={colors.gold} className="py-4" />
            ) : null}
            {footer}
          </View>
        }
        renderItem={({ item }) => (
          <View style={{ flex: 1 / columns, maxWidth: `${100 / columns}%` }}>
            {loading ? (
              <View className="gap-2">
                <Skeleton className="aspect-[2/3] w-full" testID="poster-skeleton" />
                <Skeleton className="h-4 w-3/4" />
              </View>
            ) : (
              <PosterCard
                fill
                title={item.title}
                posterUrl={item.posterUrl}
                subtitle={item.subtitle}
                score={item.score}
                onPress={onPressItem ? () => onPressItem(item) : undefined}
              />
            )}
          </View>
        )}
      />
    </View>
  );
}

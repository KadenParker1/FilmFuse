import { View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { useRating } from '../../api/feed';
import { useAuth } from '../../auth/useAuth';
import { BackButton } from '../../components/BackButton';
import { CommentsSection } from '../../components/comments/CommentsSection';
import { FeedCard, FeedCardSkeletons } from '../../components/feed/FeedCard';
import { SaveLinkAndSignIn } from '../../components/SaveLinkAndSignIn';
import { EmptyState, ErrorState, Heading, Screen } from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import { ratingPath } from '../../lib/pendingLinkPath';

const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

/** One rating with its comments. 404 (deleted, private, or a block either way) gets a friendly state. */
function RatingDetailScreen({ id }: { id: string | undefined }) {
  const rating = useRating(id);
  const notFound = !id || (rating.error instanceof ApiError && rating.error.status === 404);

  let content;
  if (notFound) {
    content = (
      <View testID="rating-not-found" className="flex-1">
        <EmptyState
          icon="chatbubbles-outline"
          title="This rating isn't available"
          message="It may have been deleted, or it isn't shared with you."
        />
      </View>
    );
  } else if (rating.isError) {
    content = (
      <ErrorState
        title="Couldn't load this rating"
        error={rating.error}
        onRetry={() => void rating.refetch()}
        retrying={rating.isFetching}
      />
    );
  } else if (rating.isPending) {
    content = <FeedCardSkeletons count={1} testID="rating-loading" />;
  } else {
    content = (
      <View className="gap-6 pb-12">
        <FeedCard item={rating.data} linkToDetail={false} />
        <CommentsSection ratingId={rating.data.id} />
      </View>
    );
  }

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/" />
        <Heading size="md">Rating</Heading>
      </View>
      {content}
    </Screen>
  );
}

/**
 * `/ratings/<id>` — where comment emails link. Reachable signed out: it remembers the link and
 * sends you to sign in; the tabs layout reopens it afterwards.
 */
export default function RatingDetailRoute() {
  const { session } = useAuth();
  const id = first(useLocalSearchParams<{ id: string }>().id);
  if (!session && id) return <SaveLinkAndSignIn path={ratingPath(id)} label="Sign in to see this rating" />;
  return <RatingDetailScreen id={id} />;
}

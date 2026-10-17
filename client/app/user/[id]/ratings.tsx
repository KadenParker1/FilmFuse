import { View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { useUserRatings } from '../../../api/feed';
import { useMe } from '../../../api/me';
import { usePublicProfile } from '../../../api/users';
import { BackButton } from '../../../components/BackButton';
import { FeedList } from '../../../components/feed/FeedList';
import { Body, EmptyState, Heading, Screen } from '../../../components/ui';
import { ApiError } from '../../../lib/apiClient';
import { personName } from '../../../lib/format';

const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

/** `/user/<id>/ratings` — every rating of theirs you may see (your own include private ones). */
export default function UserRatingsScreen() {
  const id = first(useLocalSearchParams<{ id: string }>().id);
  const me = useMe();
  const profile = usePublicProfile(id);
  const ratings = useUserRatings(id);
  const isSelf = !!id && me.data?.id === id;
  const notFound =
    !id ||
    (ratings.error instanceof ApiError && ratings.error.status === 404) ||
    (profile.error instanceof ApiError && profile.error.status === 404);
  const name = profile.data ? personName(profile.data) : null;

  return (
    <Screen className="gap-4 pb-0">
      <BackButton fallback={id ? `/user/${id}` : '/'} />
      <View className="gap-1">
        <Heading>{isSelf ? 'Your ratings' : 'Ratings'}</Heading>
        {name && !isSelf ? <Body muted>{name}</Body> : null}
      </View>
      {notFound ? (
        <EmptyState
          icon="person-remove-outline"
          title="These ratings aren't available"
          message="The account may have been deleted, or the link is wrong."
        />
      ) : (
        <FeedList
          testID="user-ratings-all"
          query={ratings}
          showAuthor={false}
          empty={
            <EmptyState
              icon="star-outline"
              title={isSelf ? "You haven't rated anything yet" : 'No ratings to show'}
              message={
                isSelf
                  ? 'Tap “+ Rate” after you watch something.'
                  : `${name ?? 'They'} ${name ? "hasn't" : "haven't"} shared any ratings with you yet.`
              }
            />
          }
        />
      )}
    </Screen>
  );
}

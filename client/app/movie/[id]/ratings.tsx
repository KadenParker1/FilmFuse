import { useState } from 'react';
import { View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import { useMovieRatings } from '../../../api/feed';
import { useMovie } from '../../../api/movies';
import { BackButton } from '../../../components/BackButton';
import { FeedList } from '../../../components/feed/FeedList';
import { SCOPE_CHOICES } from '../../../components/feed/FeedPanel';
import { Body, ChoiceGroup, EmptyState, Heading, Screen } from '../../../components/ui';
import { ApiError } from '../../../lib/apiClient';
import type { FeedScope } from '../../../types/api';

const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v);

/** `/movie/<id>/ratings?scope=` — every visible rating of one movie, Friends or Everyone. */
export default function MovieRatingsScreen() {
  const params = useLocalSearchParams<{ id: string; scope?: string }>();
  const id = Number(first(params.id));
  const valid = Number.isInteger(id) && id > 0;
  const [scope, setScope] = useState<FeedScope>(first(params.scope) === 'EVERYONE' ? 'EVERYONE' : 'FRIENDS');
  const movie = useMovie(id);
  const ratings = useMovieRatings(valid ? id : null, scope);
  const notFound = !valid || (ratings.error instanceof ApiError && ratings.error.status === 404);

  return (
    <Screen className="gap-4 pb-0">
      <BackButton fallback={valid ? `/movie/${id}` : '/discover'} />
      <View className="gap-1">
        <Heading>Ratings</Heading>
        {movie.data ? <Body muted>{movie.data.title}</Body> : null}
      </View>
      {notFound ? (
        <EmptyState icon="help-circle-outline" title="Movie not found" message="We couldn't find that movie." />
      ) : (
        <>
          <ChoiceGroup label="Whose ratings" choices={SCOPE_CHOICES} value={scope} onChange={setScope} layout="fill" />
          <FeedList
            key={scope}
            testID="movie-ratings-all"
            query={ratings}
            showMovie={false}
            empty={
              <EmptyState
                icon={scope === 'FRIENDS' ? 'people-outline' : 'globe-outline'}
                title={scope === 'FRIENDS' ? 'No ratings from friends yet' : 'No public ratings yet'}
                message={
                  scope === 'FRIENDS'
                    ? "When your friends rate this movie, you'll see it here."
                    : 'Ratings people share publicly will show up here.'
                }
              />
            }
          />
        </>
      )}
    </Screen>
  );
}

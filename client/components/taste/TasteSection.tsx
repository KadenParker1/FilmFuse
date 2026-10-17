import { View } from 'react-native';
import { useRouter } from 'expo-router';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useTasteProfile } from '../../api/taste';
import { useTheme } from '../../theme/useTheme';
import { Button, ErrorState, Heading, Skeleton } from '../ui';
import { PersonaCard } from './PersonaCard';

/** Profile tab: the persona card with links into the Taste editor and history. */
export function TasteSection() {
  const router = useRouter();
  const { colors } = useTheme();
  const taste = useTasteProfile();

  let body;
  if (taste.isPending) {
    body = <Skeleton testID="taste-loading" className="h-48 w-full" />;
  } else if (taste.isError) {
    body = (
      <ErrorState
        compact
        title="Couldn't load your taste"
        error={taste.error}
        onRetry={() => void taste.refetch()}
        retrying={taste.isFetching}
      />
    );
  } else {
    body = <PersonaCard profile={taste.data} />;
  }

  return (
    <View className="gap-3">
      <Heading size="sm">Your taste</Heading>
      {body}
      <View className="flex-row gap-3">
        <Button
          title="Edit taste"
          variant="secondary"
          className="flex-1"
          icon={<Ionicons name="options-outline" size={18} color={colors.text} />}
          onPress={() => router.push('/taste')}
        />
        <Button
          title="How it changed"
          variant="secondary"
          className="flex-1"
          icon={<Ionicons name="time-outline" size={18} color={colors.text} />}
          onPress={() => router.push('/taste/history')}
        />
      </View>
    </View>
  );
}

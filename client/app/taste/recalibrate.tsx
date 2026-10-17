import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { BackButton } from '../../components/BackButton';
import { useQuickRate } from '../../components/rating/QuickRateProvider';
import { CalibrationDeck } from '../../components/taste/CalibrationDeck';
import { Body, Heading, Screen } from '../../components/ui';

/** Taste editor → Recalibrate: the onboarding card deck again, finishable at any point. */
export default function RecalibrateScreen() {
  const router = useRouter();
  const { showTasteUpdating } = useQuickRate();

  const done = ({ answered }: { answered: number }) => {
    if (answered > 0) showTasteUpdating();
    if (router.canGoBack()) router.back();
    else router.replace('/taste');
  };

  return (
    <Screen scroll>
      <View className="mb-4 flex-row items-center gap-3">
        <BackButton fallback="/taste" />
        <Heading size="md">Recalibrate</Heading>
      </View>
      <Body muted className="mb-4">
        Score movies you’ve seen to fine-tune your taste. Stop whenever you like.
      </Body>
      <CalibrationDeck deckId="recalibrate" minRated={0} skipAfter={null} onFinish={done} />
    </Screen>
  );
}

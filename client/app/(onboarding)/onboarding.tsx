import { View } from 'react-native';
import { Body, Button, EmptyState, Screen } from '../../components/ui';
import { useAuth } from '../../auth/useAuth';

export default function OnboardingScreen() {
  const { signOut } = useAuth();
  return (
    <Screen>
      <EmptyState
        icon="sparkles-outline"
        title="Let's learn your taste"
        message="Onboarding coming in Phase 1b — genres, all-time favourites, a quick calibration swipe and your taste persona."
      />
      <View className="gap-2 pb-4">
        <Body muted className="text-center text-sm">
          Not you?
        </Body>
        <Button variant="ghost" title="Sign out" onPress={() => void signOut()} />
      </View>
    </Screen>
  );
}

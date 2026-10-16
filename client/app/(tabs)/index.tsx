import { EmptyState, Heading, Screen } from '../../components/ui';

export default function SocialScreen() {
  return (
    <Screen edges={['top']}>
      <Heading>Social</Heading>
      <EmptyState
        icon="chatbubbles-outline"
        title="Nothing on the marquee yet"
        message="Your friends' ratings will appear here — Phase 2."
      />
    </Screen>
  );
}

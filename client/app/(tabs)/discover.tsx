import { EmptyState, Heading, Screen } from '../../components/ui';

export default function DiscoverScreen() {
  return (
    <Screen edges={['top']}>
      <Heading>Discover</Heading>
      <EmptyState
        icon="compass-outline"
        title="Find your next favourite"
        message="Movie search arrives in Phase 1a and personal “For you” picks in Phase 1b."
      />
    </Screen>
  );
}

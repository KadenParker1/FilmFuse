import { EmptyState, Heading, Screen } from '../../components/ui';

export default function GroupsScreen() {
  return (
    <Screen edges={['top']}>
      <Heading>Groups</Heading>
      <EmptyState
        icon="albums-outline"
        title="Movie night, together"
        message="Saved groups and ad-hoc “Tonight” sessions will live here — Phase 3."
      />
    </Screen>
  );
}

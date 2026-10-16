import { Link } from 'expo-router';
import { EmptyState, Screen } from '../components/ui';

export default function NotFound() {
  return (
    <Screen>
      <EmptyState icon="help-circle-outline" title="This reel is missing" message="That page doesn't exist." />
      <Link href="/" className="self-center p-4 font-body-semibold text-gold">
        Back to FilmFuse
      </Link>
    </Screen>
  );
}

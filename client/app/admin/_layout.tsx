import { Stack } from 'expo-router';
import { useMe } from '../../api/me';
import { FullScreenLoader } from '../../components/FullScreenLoader';
import { stackScreenOptions } from '../../theme/navigation';
import { useTheme } from '../../theme/useTheme';
import NotFound from '../+not-found';

/**
 * The admin area. Shown only when `/me` says `isAdmin`; anyone else (or a failed /me) gets the normal
 * not-found screen, so a non-admin opening /admin learns nothing. This is UI gating only — every
 * /api/v1/admin endpoint is enforced server-side and answers non-admins 404.
 */
export default function AdminLayout() {
  const me = useMe();
  const { colors } = useTheme();

  if (me.isPending) return <FullScreenLoader />;
  if (!me.data?.isAdmin) return <NotFound />;
  return <Stack screenOptions={stackScreenOptions(colors.bg)} />;
}

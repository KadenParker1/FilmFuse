import { View } from 'react-native';
import { useRouter } from 'expo-router';
import { useCatalogMetrics, useHealthMetrics, useUsageMetrics } from '../../api/admin';
import { AdminFrame } from '../../components/admin/AdminFrame';
import { DailyBars, SizeMeter } from '../../components/admin/Charts';
import { MetricCard, MetricRow } from '../../components/admin/MetricCard';
import { Button, Card, ErrorState, Label, Skeleton } from '../../components/ui';
import { formatCount, formatMb, percent } from '../../lib/admin';
import { timeAgo } from '../../lib/format';

function SectionSkeleton({ testID }: { testID: string }) {
  return (
    <View testID={testID} className="gap-3">
      <Skeleton className="h-5 w-1/3" />
      <Skeleton className="h-24 w-full" />
    </View>
  );
}

function Usage() {
  const usage = useUsageMetrics();
  if (usage.isPending) return <SectionSkeleton testID="usage-loading" />;
  if (usage.isError) {
    return <ErrorState compact title="Couldn't load usage" error={usage.error} onRetry={() => void usage.refetch()} />;
  }
  const u = usage.data;
  return (
    <View className="gap-3">
      <Label>Usage</Label>
      <MetricRow>
        <MetricCard testID="metric-users" label="Users" value={formatCount(u.usersTotal)} hint={`${formatCount(u.usersNew7d)} new this week`} />
        <MetricCard
          label="Onboarded"
          value={formatCount(u.usersOnboarded)}
          hint={`${percent(u.usersOnboarded, u.usersTotal)} of users`}
        />
        <MetricCard
          label="Sessions started"
          value={formatCount(u.sessionsStarted7d)}
          hint={`7 days · ${formatCount(u.sessionsStarted30d)} in 30`}
        />
        <MetricCard
          label="Movies picked"
          value={formatCount(u.sessionsPicked7d)}
          hint={`7 days · ${formatCount(u.sessionsPicked30d)} in 30`}
        />
        <MetricCard label="Active groups" value={formatCount(u.activeGroups30d)} hint="a session in 30 days" />
      </MetricRow>
      <Card>
        <DailyBars data={u.ratingsPerDay} label="Ratings per day (UTC, 30 days)" />
      </Card>
    </View>
  );
}

function Health() {
  const router = useRouter();
  const health = useHealthMetrics();
  if (health.isPending) return <SectionSkeleton testID="health-loading" />;
  if (health.isError) {
    return <ErrorState compact title="Couldn't load health" error={health.error} onRetry={() => void health.refetch()} />;
  }
  const h = health.data;
  const ml = !h.ml.configured
    ? { value: 'Fallback', hint: 'ML_SERVICE_URL not set', tone: 'warn' as const }
    : h.ml.reachable
      ? {
          value: 'Up',
          hint: [h.ml.modelVersion ? `model ${h.ml.modelVersion}` : null, h.ml.latencyMs != null ? `${h.ml.latencyMs} ms` : null]
            .filter(Boolean)
            .join(' · '),
          tone: 'good' as const,
        }
      : { value: 'Down', hint: 'health check failed', tone: 'bad' as const };
  return (
    <View className="gap-3">
      <Label>Health</Label>
      <MetricRow>
        <MetricCard testID="metric-ml" label="ML service" value={ml.value} hint={ml.hint} tone={ml.tone} />
        <MetricCard
          label="Taste vectors waiting"
          value={formatCount(h.vectorsDirty)}
          hint={h.vectorsDirtyOldest ? `oldest ${timeAgo(h.vectorsDirtyOldest)}` : `${formatCount(h.vectorsNeverBuilt)} never built`}
          tone={h.vectorsDirty > 50 ? 'warn' : 'default'}
        />
        <MetricCard
          testID="metric-reports"
          label="Open reports"
          value={formatCount(h.openReports)}
          tone={h.openReports > 0 ? 'warn' : 'default'}
        />
      </MetricRow>
      {h.openReports > 0 ? (
        <Button title="Review reports" variant="secondary" className="self-start" onPress={() => router.replace('/admin/reports')} />
      ) : null}
    </View>
  );
}

function CatalogSummary() {
  const catalog = useCatalogMetrics();
  if (catalog.isPending) return <SectionSkeleton testID="catalog-loading" />;
  if (catalog.isError) {
    return <ErrorState compact title="Couldn't load the catalog" error={catalog.error} onRetry={() => void catalog.refetch()} />;
  }
  const c = catalog.data;
  return (
    <View className="gap-3">
      <Label>Catalog</Label>
      <MetricRow>
        <MetricCard label="Movies" value={formatCount(c.totalMovies)} hint={`${percent(c.withCertification, c.totalMovies)} with a certification`} />
        <MetricCard label="Database" value={formatMb(c.databaseBytes)} hint={`guard ${c.maxDatabaseMb} MB`} />
      </MetricRow>
      <Card>
        <SizeMeter bytes={c.databaseBytes} guardMb={c.maxDatabaseMb} planMb={c.planLimitMb} />
      </Card>
    </View>
  );
}

/** Admin → Overview: usage, health and a catalog summary at a glance. */
export default function AdminOverviewScreen() {
  return (
    <AdminFrame current="overview" title="Overview">
      <Usage />
      <Health />
      <CatalogSummary />
    </AdminFrame>
  );
}

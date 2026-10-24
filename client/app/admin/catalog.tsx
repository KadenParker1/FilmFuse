import { Text, View } from 'react-native';
import { useAdminJobs, useAdminSettings, useCatalogMetrics } from '../../api/admin';
import { AdminFrame } from '../../components/admin/AdminFrame';
import { ShareBar, SizeMeter } from '../../components/admin/Charts';
import { JobCard } from '../../components/admin/JobCard';
import { MetricCard, MetricRow } from '../../components/admin/MetricCard';
import { SettingField } from '../../components/admin/SettingField';
import { Card, ErrorState, Label, Skeleton } from '../../components/ui';
import { formatCount, percent } from '../../lib/admin';
import type { AdminSettingDTO } from '../../types/api';

/** Shown beside the size guard: it stops every catalog write, not just new movies. */
export const SIZE_GUARD_NOTE =
  'The size guard stops every catalog job once the database is past it — including refresh steps that only update movies already stored. Keep it under the plan limit with room for user data.';

function CatalogStats() {
  const catalog = useCatalogMetrics();
  if (catalog.isPending) return <Skeleton testID="catalog-stats-loading" className="h-40 w-full" />;
  if (catalog.isError) {
    return <ErrorState compact title="Couldn't load catalog stats" error={catalog.error} onRetry={() => void catalog.refetch()} />;
  }
  const c = catalog.data;
  const t = c.voteTiers;
  return (
    <View className="gap-3">
      <Label>Catalog</Label>
      <MetricRow>
        <MetricCard testID="metric-movies" label="Movies" value={formatCount(c.totalMovies)} />
        <MetricCard
          label="With certification"
          value={formatCount(c.withCertification)}
          hint={percent(c.withCertification, c.totalMovies)}
        />
        <MetricCard label="Rejected ids" value={formatCount(c.rejected)} hint="failed the quality bar" />
      </MetricRow>
      <Card className="gap-5">
        <ShareBar
          label="TMDB votes"
          shares={[
            { label: '1,000+', value: t.votes1000Plus, color: 'accent' },
            { label: '100–999', value: t.votes100To999, color: 'accentInk' },
            { label: '20–99', value: t.votes20To99, color: 'accent2' },
            { label: 'Under 20', value: t.votesUnder20, color: 'danger' },
            { label: 'Unknown', value: t.votesUnknown, color: 'muted' },
          ]}
        />
        <SizeMeter bytes={c.databaseBytes} guardMb={c.maxDatabaseMb} planMb={c.planLimitMb} />
        <Text className="font-body text-xs text-muted">{SIZE_GUARD_NOTE}</Text>
      </Card>
    </View>
  );
}

function Jobs() {
  const jobs = useAdminJobs();
  if (jobs.isPending) return <Skeleton testID="jobs-loading" className="h-40 w-full" />;
  if (jobs.isError) {
    return <ErrorState compact title="Couldn't load jobs" error={jobs.error} onRetry={() => void jobs.refetch()} />;
  }
  return (
    <View className="gap-3">
      <Label>Jobs</Label>
      <View className="flex-row flex-wrap gap-3">
        {jobs.data.map((job) => (
          <View key={job.name} className="min-w-[280px] flex-1">
            <JobCard job={job} />
          </View>
        ))}
      </View>
    </View>
  );
}

/** Settings grouped by their `group`, in registry order. */
export function groupSettings(settings: AdminSettingDTO[]): [string, AdminSettingDTO[]][] {
  const groups = new Map<string, AdminSettingDTO[]>();
  settings.forEach((s) => groups.set(s.group, [...(groups.get(s.group) ?? []), s]));
  return [...groups.entries()];
}

function Settings() {
  const settings = useAdminSettings();
  if (settings.isPending) return <Skeleton testID="settings-loading" className="h-64 w-full" />;
  if (settings.isError) {
    return <ErrorState compact title="Couldn't load settings" error={settings.error} onRetry={() => void settings.refetch()} />;
  }
  return (
    <View className="gap-3">
      <Label>Runtime settings</Label>
      <Text className="font-body text-sm text-muted">
        Changes apply from the next job run — no redeploy. Every change is in the audit log.
      </Text>
      {groupSettings(settings.data).map(([group, items]) => (
        <Card key={group} className="gap-1">
          <Text accessibilityRole="header" className="font-body-bold text-base text-text">
            {group}
          </Text>
          {items.map((setting) => (
            <View key={setting.key}>
              <SettingField setting={setting} />
              {setting.key === 'catalog.max-database-mb' ? (
                <Text testID="size-guard-note" className="pb-2 font-body text-xs text-accent2">
                  {SIZE_GUARD_NOTE}
                </Text>
              ) : null}
            </View>
          ))}
        </Card>
      ))}
    </View>
  );
}

/** Admin → Catalog & jobs: catalog stats, the jobs with their controls, and the runtime settings. */
export default function AdminCatalogScreen() {
  return (
    <AdminFrame current="catalog" title="Catalog & jobs">
      <CatalogStats />
      <Jobs />
      <Settings />
    </AdminFrame>
  );
}

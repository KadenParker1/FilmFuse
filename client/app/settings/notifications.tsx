import { Text, View } from 'react-native';
import { useNotificationSettings, useUpdateNotificationSettings } from '../../api/notifications';
import { BackButton } from '../../components/BackButton';
import { Body, Card, errorMessage, ErrorState, Heading, Screen, Skeleton, Toggle } from '../../components/ui';
import type { NotificationSettingsDTO } from '../../types/api';

type Pref = keyof NotificationSettingsDTO;

/** Every email preference, in display order, with what it means. */
export const NOTIFICATION_PREFS: { key: Pref; label: string; description: string }[] = [
  {
    key: 'friendRequests',
    label: 'Friend requests',
    description: 'When someone sends you a friend request, or accepts yours.',
  },
  { key: 'groupInvites', label: 'Group invites', description: 'When a friend adds you to a group.' },
  {
    key: 'sessionStarted',
    label: 'Movie sessions',
    description: 'When someone you watch with starts a movie session that includes you.',
  },
  { key: 'comments', label: 'Comments', description: 'When someone comments on one of your ratings.' },
  {
    key: 'weeklyDigest',
    label: 'Weekly digest',
    description: "Once a week, your friends' top-rated movies. Off unless you turn it on.",
  },
];

function NotificationsForm({ settings }: { settings: NotificationSettingsDTO }) {
  const update = useUpdateNotificationSettings();

  return (
    <View className="gap-4 pb-12">
      <Body muted>FilmFuse only emails you about what&apos;s switched on here. Changes save straight away.</Body>
      <Card className="gap-1 py-2">
        {NOTIFICATION_PREFS.map(({ key, label, description }) => (
          <View key={key} testID={`pref-${key}`} className="flex-row items-center gap-4 py-3">
            <View className="flex-1 gap-0.5">
              <Text className="font-body-semibold text-base text-text">{label}</Text>
              <Body muted className="text-sm">
                {description}
              </Body>
            </View>
            <Toggle
              accessibilityLabel={label}
              accessibilityHint={description}
              accessibilityState={{ checked: settings[key], disabled: update.isPending }}
              value={settings[key]}
              disabled={update.isPending}
              onValueChange={(next) => update.mutate({ ...settings, [key]: next })}
            />
          </View>
        ))}
      </Card>
      {update.isError ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          Couldn&apos;t save that change. {errorMessage(update.error)}
        </Body>
      ) : update.isSuccess ? (
        <Body accessibilityLiveRegion="polite" className="text-sm text-success">
          Saved.
        </Body>
      ) : null}
    </View>
  );
}

/** Settings → Notifications: which emails FilmFuse sends you. */
export default function NotificationSettingsScreen() {
  const settings = useNotificationSettings();

  let content;
  if (settings.isPending) {
    content = (
      <View testID="notifications-loading" className="gap-4">
        <Skeleton className="h-6 w-3/4" />
        <Skeleton className="h-72 w-full" />
      </View>
    );
  } else if (settings.isError) {
    content = (
      <ErrorState
        title="Couldn't load your notification settings"
        error={settings.error}
        onRetry={() => void settings.refetch()}
        retrying={settings.isFetching}
      />
    );
  } else {
    content = <NotificationsForm settings={settings.data} />;
  }

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/profile" />
        <Heading size="md">Notifications</Heading>
      </View>
      {content}
    </Screen>
  );
}

import { useState } from 'react';
import { Switch, Text, View } from 'react-native';
import { useComfortSettings, useUpdateComfortSettings } from '../../api/comfort';
import { BackButton } from '../../components/BackButton';
import { CertificationPicker } from '../../components/comfort/CertificationPicker';
import { ContentLimitsEditor } from '../../components/comfort/ContentLevelPicker';
import { GROUP_PRIVACY_NOTE, sameLimits, validateComfort } from '../../components/comfort/comfortCopy';
import { Body, Button, Card, ErrorState, Heading, Label, Screen, Skeleton, errorMessage } from '../../components/ui';
import { ApiError } from '../../lib/apiClient';
import { useTheme } from '../../theme/useTheme';
import type { ComfortSettingsDTO } from '../../types/api';

const sameSettings = (a: ComfortSettingsDTO, b: ComfortSettingsDTO) =>
  a.maxCertification === b.maxCertification && a.allowUnrated === b.allowUnrated && sameLimits(a.contentLimits, b.contentLimits);

function ComfortForm({ initial }: { initial: ComfortSettingsDTO }) {
  const { colors } = useTheme();
  const update = useUpdateComfortSettings();
  const [draft, setDraft] = useState<ComfortSettingsDTO>(initial);
  const [saved, setSaved] = useState<ComfortSettingsDTO>(initial);
  const [localError, setLocalError] = useState<string | null>(null);
  const dirty = !sameSettings(draft, saved);

  const edit = (patch: Partial<ComfortSettingsDTO>) => {
    setLocalError(null);
    update.reset();
    setDraft((d) => ({ ...d, ...patch }));
  };

  const save = () => {
    const problem = validateComfort(draft);
    if (problem) {
      setLocalError(problem);
      return;
    }
    update.mutate(draft, {
      onSuccess: (settings) => {
        setSaved(settings);
        setDraft(settings);
      },
    });
  };

  const serverError =
    update.error instanceof ApiError && Object.keys(update.error.fieldErrors).length
      ? Object.values(update.error.fieldErrors).join(' ')
      : update.isError
        ? errorMessage(update.error)
        : null;
  const error = localError ?? serverError;

  return (
    <View className="gap-6 pb-12">
      <Body muted>
        These are hard limits, not preferences: FilmFuse never recommends anything above them, whatever the scores
        say.
      </Body>

      <Card className="gap-4">
        <CertificationPicker
          title="Highest rating you’ll watch"
          value={draft.maxCertification}
          onChange={(maxCertification) => edit({ maxCertification })}
        />
      </Card>

      <Card className="gap-4">
        <Label accessibilityRole="header">Within that, how much…</Label>
        <ContentLimitsEditor value={draft.contentLimits} onChange={(contentLimits) => edit({ contentLimits })} />
      </Card>

      <Card className="flex-row items-center gap-4">
        <View className="flex-1 gap-1">
          <Text className="font-body-semibold text-base text-text">Allow movies with unknown ratings</Text>
          <Body muted className="text-sm">
            Some movies have no rating or content details. Off keeps them out when you’ve set a limit.
          </Body>
        </View>
        <Switch
          accessibilityLabel="Allow movies with unknown ratings"
          value={draft.allowUnrated}
          onValueChange={(allowUnrated) => edit({ allowUnrated })}
          trackColor={{ true: colors.gold, false: colors.border }}
        />
      </Card>

      <Body muted testID="comfort-privacy-note" className="text-sm">
        {GROUP_PRIVACY_NOTE}
      </Body>

      {error ? (
        <Body accessibilityRole="alert" className="text-sm text-velvet">
          {error}
        </Body>
      ) : update.isSuccess && !dirty ? (
        <Body accessibilityLiveRegion="polite" className="text-sm text-success">
          Saved.
        </Body>
      ) : null}

      <Button title="Save" loading={update.isPending} disabled={!dirty} onPress={save} />
    </View>
  );
}

/** Settings → Comfort settings: the caller's content limits. Onboarding step 6 reuses the pickers. */
export default function ComfortSettingsScreen() {
  const comfort = useComfortSettings();

  let content;
  if (comfort.isPending) {
    content = (
      <View testID="comfort-loading" className="gap-4">
        <Skeleton className="h-28 w-full" />
        <Skeleton className="h-64 w-full" />
      </View>
    );
  } else if (comfort.isError) {
    content = (
      <ErrorState
        title="Couldn't load your comfort settings"
        error={comfort.error}
        onRetry={() => void comfort.refetch()}
        retrying={comfort.isFetching}
      />
    );
  } else {
    content = <ComfortForm initial={comfort.data} />;
  }

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/profile" />
        <Heading size="md">Comfort settings</Heading>
      </View>
      {content}
    </Screen>
  );
}

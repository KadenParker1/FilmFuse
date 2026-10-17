import { Text, View } from 'react-native';
import type { ComfortSettingsDTO } from '../../types/api';
import { Body, Card, Label, Toggle } from '../ui';
import { CertificationPicker } from './CertificationPicker';
import { limitsForCertification } from './comfortCopy';
import { ContentLimitsEditor } from './ContentLevelPicker';

type Props = {
  value: ComfortSettingsDTO;
  onChange: (patch: Partial<ComfortSettingsDTO>) => void;
};

/** The comfort settings form body: highest rating, per-dimension limits, unknown ratings. Settings and onboarding step 6. */
export function ComfortFields({ value, onChange }: Props) {
  return (
    <>
      <Card className="gap-4">
        <CertificationPicker
          title="Highest rating you’ll watch"
          value={value.maxCertification}
          // Lowering below R hides "Sex & nudity", so its value is dropped rather than saved hidden.
          onChange={(maxCertification) =>
            onChange({ maxCertification, contentLimits: limitsForCertification(maxCertification, value.contentLimits) })
          }
        />
      </Card>

      <Card className="gap-4">
        <Label accessibilityRole="header">Within that, how much…</Label>
        <ContentLimitsEditor
          value={value.contentLimits}
          maxCertification={value.maxCertification}
          onChange={(contentLimits) => onChange({ contentLimits })}
        />
      </Card>

      <Card className="flex-row items-center gap-4">
        <View className="flex-1 gap-1">
          <Text className="font-body-semibold text-base text-text">Allow movies with unknown ratings</Text>
          <Body muted className="text-sm">
            Some movies have no rating or content details. Off keeps them out when you’ve set a limit.
          </Body>
        </View>
        <Toggle
          accessibilityLabel="Allow movies with unknown ratings"
          value={value.allowUnrated}
          onValueChange={(allowUnrated) => onChange({ allowUnrated })}
        />
      </Card>
    </>
  );
}

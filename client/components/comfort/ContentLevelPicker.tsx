import { Text, View } from 'react-native';
import type { Certification, ContentDimension, ContentLevel } from '../../types/api';
import { Body, ChoiceGroup } from '../ui';
import {
  DIMENSION_LABELS,
  LEVEL_LABELS,
  clampLevel,
  levelExample,
  levelFor,
  levelsFor,
  visibleDimensions,
  withLevel,
} from './comfortCopy';

type Props = {
  dimension: ContentDimension;
  value: ContentLevel;
  onChange: (level: ContentLevel) => void;
  disabled?: boolean;
};

/**
 * One dimension's ceiling with an example of what it allows: None / Mild / Moderate / Strong, or from
 * Mild for violence and frightening (see MIN_LEVEL). A value below the minimum shows as the minimum.
 */
export function ContentLevelPicker({ dimension, value, onChange, disabled }: Props) {
  const name = DIMENSION_LABELS[dimension];
  const shown = clampLevel(dimension, value);
  return (
    <View testID={`level-picker-${dimension}`} className="gap-2">
      <Text className="font-body-semibold text-base text-text">{name}</Text>
      <ChoiceGroup
        label={name}
        layout="fill"
        disabled={disabled}
        choices={levelsFor(dimension).map((l) => ({
          value: l,
          label: LEVEL_LABELS[l],
          accessibilityLabel: `${name}: ${LEVEL_LABELS[l]}`,
        }))}
        value={shown}
        onChange={onChange}
      />
      <Body muted testID={`level-example-${dimension}`} className="text-sm">
        {levelExample(dimension, shown)}
      </Body>
    </View>
  );
}

type EditorProps = {
  value: Partial<Record<ContentDimension, ContentLevel>>;
  onChange: (value: Partial<Record<ContentDimension, ContentLevel>>) => void;
  /** The chosen maximum rating: below R the "Sex & nudity" row is hidden. */
  maxCertification: Certification | null;
  disabled?: boolean;
};

/** Every visible dimension's picker, editing one limits map (a missing dimension = Strong / no limit). */
export function ContentLimitsEditor({ value, onChange, maxCertification, disabled }: EditorProps) {
  return (
    <View className="gap-5">
      {visibleDimensions(maxCertification).map((d) => (
        <ContentLevelPicker
          key={d}
          dimension={d}
          value={levelFor(value, d)}
          disabled={disabled}
          onChange={(level) => onChange(withLevel(value, d, level))}
        />
      ))}
    </View>
  );
}

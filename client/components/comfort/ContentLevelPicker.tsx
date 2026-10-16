import { Text, View } from 'react-native';
import type { ContentDimension, ContentLevel } from '../../types/api';
import { Body, ChoiceGroup } from '../ui';
import {
  CONTENT_DIMENSIONS,
  CONTENT_LEVELS,
  DIMENSION_LABELS,
  LEVEL_EXAMPLES,
  LEVEL_LABELS,
  levelFor,
  withLevel,
} from './comfortCopy';

type Props = {
  dimension: ContentDimension;
  value: ContentLevel;
  onChange: (level: ContentLevel) => void;
  disabled?: boolean;
};

/** One dimension's ceiling (None / Mild / Moderate / Strong) with an example of what it allows. */
export function ContentLevelPicker({ dimension, value, onChange, disabled }: Props) {
  const name = DIMENSION_LABELS[dimension];
  return (
    <View testID={`level-picker-${dimension}`} className="gap-2">
      <Text className="font-body-semibold text-base text-text">{name}</Text>
      <ChoiceGroup
        label={name}
        layout="fill"
        disabled={disabled}
        choices={CONTENT_LEVELS.map((l) => ({
          value: l,
          label: LEVEL_LABELS[l],
          accessibilityLabel: `${name}: ${LEVEL_LABELS[l]}`,
        }))}
        value={value}
        onChange={onChange}
      />
      <Body muted testID={`level-example-${dimension}`} className="text-sm">
        {LEVEL_EXAMPLES[dimension][value]}
      </Body>
    </View>
  );
}

type EditorProps = {
  value: Partial<Record<ContentDimension, ContentLevel>>;
  onChange: (value: Partial<Record<ContentDimension, ContentLevel>>) => void;
  disabled?: boolean;
};

/** Every dimension's picker, editing one limits map (a missing dimension = Strong / no limit). */
export function ContentLimitsEditor({ value, onChange, disabled }: EditorProps) {
  return (
    <View className="gap-5">
      {CONTENT_DIMENSIONS.map((d) => (
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

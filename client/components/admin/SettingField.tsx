import { useState } from 'react';
import { Text, View } from 'react-native';
import { useResetAdminSetting, useUpdateAdminSetting } from '../../api/admin';
import { formatCount, validateIntegerSetting } from '../../lib/admin';
import { ApiError } from '../../lib/apiClient';
import type { AdminSettingDTO } from '../../types/api';
import { Body, Button, errorMessage, TextField, Toggle } from '../ui';

/** The server's word on a rejected value (fieldErrors.value), else a general message. */
function serverError(error: unknown): string | null {
  if (!error) return null;
  if (error instanceof ApiError && error.fieldErrors.value) return error.fieldErrors.value;
  return errorMessage(error);
}

function describeDefault(setting: AdminSettingDTO): string {
  const value =
    typeof setting.defaultValue === 'boolean' ? (setting.defaultValue ? 'on' : 'off') : formatCount(setting.defaultValue);
  const range =
    setting.type === 'INTEGER' && setting.min != null && setting.max != null
      ? ` · allowed ${formatCount(setting.min)}–${formatCount(setting.max)}${setting.unit ? ` ${setting.unit}` : ''}`
      : '';
  return `Default ${value}${setting.unit && setting.type === 'INTEGER' ? ` ${setting.unit}` : ''}${range}`;
}

/**
 * One runtime setting. Numbers: typed, checked inline against the bounds as you type, saved with Save
 * (Enter on web/hardware keyboards too). Booleans: a toggle that saves at once. "Use default"
 * removes the override. Every save is audited server-side.
 */
export function SettingField({ setting }: { setting: AdminSettingDTO }) {
  const update = useUpdateAdminSetting();
  const reset = useResetAdminSetting();
  // null = not editing: the field shows the server's value (so it follows saves, resets and refetches).
  const [draft, setDraft] = useState<string | null>(null);
  const text = draft ?? String(setting.value);
  const touched = draft !== null;

  const busy = update.isPending || reset.isPending;
  const failure = serverError(update.error ?? reset.error);
  const meta = (
    <Text className="font-body text-xs text-muted">
      {describeDefault(setting)}
      {setting.overridden ? ' · overridden' : ''}
    </Text>
  );

  if (setting.type === 'BOOLEAN') {
    const on = setting.value === true;
    return (
      <View testID={`setting-${setting.key}`} className="gap-1 py-3">
        <View className="flex-row items-center gap-4">
          <View className="flex-1 gap-0.5">
            <Text className="font-body-semibold text-base text-text">{setting.label}</Text>
            <Body muted className="text-sm">
              {setting.description}
            </Body>
          </View>
          <Toggle
            accessibilityLabel={setting.label}
            accessibilityHint={setting.description}
            accessibilityState={{ checked: on, disabled: busy }}
            value={on}
            disabled={busy}
            onValueChange={(next) => update.mutate({ key: setting.key, value: next })}
          />
        </View>
        {meta}
        {setting.overridden ? (
          <Button
            title="Use default"
            variant="ghost"
            size="sm"
            className="self-start"
            disabled={busy}
            onPress={() => reset.mutate(setting.key)}
          />
        ) : null}
        {failure ? (
          <Text accessibilityRole="alert" className="font-body text-sm text-danger">
            {failure}
          </Text>
        ) : null}
      </View>
    );
  }

  const check = validateIntegerSetting(setting, text);
  const dirty = text.trim() !== String(setting.value);
  const inlineError = touched && !check.ok ? check.error : null;
  const save = () => {
    if (!check.ok || !dirty) {
      setDraft(text);
      return;
    }
    update.mutate({ key: setting.key, value: check.value }, { onSuccess: () => setDraft(null) });
  };

  return (
    <View testID={`setting-${setting.key}`} className="gap-2 py-3">
      <TextField
        label={`${setting.label}${setting.unit ? ` (${setting.unit})` : ''}`}
        hint={setting.description}
        value={text}
        onChangeText={(next) => {
          setDraft(next);
          if (update.isError) update.reset();
        }}
        onSubmitEditing={save}
        keyboardType="number-pad"
        inputMode="numeric"
        returnKeyType="done"
        editable={!busy}
        error={inlineError ?? (update.isError ? failure : null)}
      />
      {meta}
      <View className="flex-row flex-wrap gap-2">
        <Button
          title="Save"
          size="sm"
          loading={update.isPending}
          disabled={busy || !dirty || !check.ok}
          onPress={save}
          accessibilityLabel={`Save ${setting.label}`}
        />
        {dirty ? (
          <Button
            title="Cancel"
            size="sm"
            variant="secondary"
            disabled={busy}
            onPress={() => {
              setDraft(null);
              update.reset();
            }}
            accessibilityLabel={`Cancel editing ${setting.label}`}
          />
        ) : null}
        {setting.overridden ? (
          <Button
            title="Use default"
            size="sm"
            variant="ghost"
            loading={reset.isPending}
            disabled={busy}
            onPress={() => {
              setDraft(null);
              reset.mutate(setting.key);
            }}
            accessibilityLabel={`Reset ${setting.label} to its default`}
          />
        ) : null}
      </View>
      {reset.isError ? (
        <Text accessibilityRole="alert" className="font-body text-sm text-danger">
          {serverError(reset.error)}
        </Text>
      ) : null}
      {update.isSuccess && !dirty ? (
        <Text accessibilityLiveRegion="polite" className="font-body text-sm text-success">
          Saved. It applies from the next run.
        </Text>
      ) : null}
    </View>
  );
}

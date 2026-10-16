import { Text, View } from 'react-native';
import { normalizeUsername, usernameStatus, useUsernameAvailability, type UsernameStatus } from '../../api/usernames';
import { useDebouncedValue } from '../../lib/useDebouncedValue';
import { TextField } from '../ui';
import { usernameStatusText } from './usernameStatusText';

export const DISPLAY_NAME_MAX = 77;
export const USERNAME_MAX = 30;

/**
 * Live availability for the username being typed: debounced server check, with typing ahead of the
 * debounce counted as still checking. `current` is the caller's own username (never "taken").
 */
export function useUsernameCheck(username: string, current: string | null | undefined): UsernameStatus {
  const debouncedUsername = useDebouncedValue(username, 300);
  const availability = useUsernameAvailability(debouncedUsername, current);
  return usernameStatus({
    username,
    current,
    isFetching: availability.isFetching || normalizeUsername(username) !== normalizeUsername(debouncedUsername),
    isError: availability.isError,
    data: availability.data,
  });
}

type Props = {
  displayName: string;
  onDisplayNameChange: (value: string) => void;
  username: string;
  onUsernameChange: (value: string) => void;
  /** From {@link useUsernameCheck}; drives the line under the username field. */
  usernameStatus: UsernameStatus;
  /** Server fieldErrors from the last PATCH /me; they replace the live status line. */
  fieldErrors?: Record<string, string>;
};

const toneClass = { muted: 'text-text-muted', good: 'text-success', bad: 'text-velvet' } as const;

/** Display name + username with live availability. Shared by edit profile and onboarding. */
export function ProfileBasicsForm({
  displayName,
  onDisplayNameChange,
  username,
  onUsernameChange,
  usernameStatus: status,
  fieldErrors = {},
}: Props) {
  const statusText = usernameStatusText(status);
  return (
    <>
      <TextField
        label="Display name"
        value={displayName}
        onChangeText={onDisplayNameChange}
        maxLength={DISPLAY_NAME_MAX}
        autoComplete="name"
        placeholder="How friends see you"
        error={fieldErrors.displayName}
      />
      <View className="gap-1.5">
        <TextField
          label="Username"
          value={username}
          onChangeText={onUsernameChange}
          autoCapitalize="none"
          autoCorrect={false}
          autoComplete="username"
          maxLength={USERNAME_MAX}
          placeholder="e.g. popcorn_paula"
          error={fieldErrors.username}
        />
        {!fieldErrors.username && statusText ? (
          <Text
            testID="username-status"
            accessibilityLiveRegion="polite"
            className={`font-body text-sm ${toneClass[statusText.tone]}`}
          >
            {statusText.text}
          </Text>
        ) : null}
      </View>
    </>
  );
}

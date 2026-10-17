import { useState } from 'react';
import { Text, View } from 'react-native';
import { useRouter } from 'expo-router';
import { MAX_INVITEES, useCreateGroup } from '../../api/groups';
import { BackButton } from '../../components/BackButton';
import { FriendPicker } from '../../components/groups/FriendPicker';
import { ColorSwatches, EmojiPicker } from '../../components/groups/GroupStylePickers';
import { DEFAULT_GROUP_COLOR, DEFAULT_GROUP_EMOJI, groupColor } from '../../components/groups/groupStyle';
import { validateGroupForm, type GroupFormErrors } from '../../components/groups/validateGroup';
import { Body, Button, Card, Heading, Screen, TextField, errorMessage } from '../../components/ui';
import { ApiError } from '../../lib/apiClient';

/** Create a saved group: name, emoji, colour and which friends to invite. */
export default function NewGroupScreen() {
  const router = useRouter();
  const create = useCreateGroup();
  const [name, setName] = useState('');
  const [emoji, setEmoji] = useState<string>(DEFAULT_GROUP_EMOJI);
  const [color, setColor] = useState<string>(DEFAULT_GROUP_COLOR);
  const [memberIds, setMemberIds] = useState<string[]>([]);
  const [errors, setErrors] = useState<GroupFormErrors>({});

  const submit = () => {
    const found = validateGroupForm(name, memberIds);
    setErrors(found);
    if (found.name || found.members) return;
    create.mutate(
      { name: name.trim(), emoji, color, memberIds },
      { onSuccess: (group) => router.replace(`/groups/${group.id}`) },
    );
  };

  const fieldErrors = create.error instanceof ApiError ? create.error.fieldErrors : {};
  const nameError = errors.name ?? fieldErrors.name ?? null;
  const generalError = create.isError && !fieldErrors.name ? errorMessage(create.error) : null;

  return (
    <Screen scroll>
      <View className="mb-6 flex-row items-center gap-3">
        <BackButton fallback="/groups" />
        <Heading size="md">New group</Heading>
      </View>
      <View className="gap-6 pb-12">
        <Card className="flex-row items-center gap-4">
          <View
            testID="group-preview"
            className="h-14 w-14 items-center justify-center rounded-2xl"
            style={{ backgroundColor: `${groupColor(color)}33`, borderColor: groupColor(color), borderWidth: 2 }}
          >
            <Text className="text-3xl">{emoji}</Text>
          </View>
          <View className="flex-1">
            <TextField
              label="Group name"
              placeholder="Friday film club"
              value={name}
              maxLength={60}
              onChangeText={(t) => {
                setName(t);
                if (errors.name) setErrors((e) => ({ ...e, name: undefined }));
              }}
              error={nameError}
            />
          </View>
        </Card>
        <EmojiPicker value={emoji} onChange={setEmoji} />
        <ColorSwatches value={color} onChange={setColor} />
        <FriendPicker
          title="Invite friends"
          selected={memberIds}
          onChange={(ids) => {
            setMemberIds(ids);
            if (errors.members) setErrors((e) => ({ ...e, members: undefined }));
          }}
          max={MAX_INVITEES}
        />
        {errors.members ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {errors.members}
          </Body>
        ) : null}
        {generalError ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {generalError}
          </Body>
        ) : null}
        <Button title="Create group" loading={create.isPending} onPress={submit} />
      </View>
    </Screen>
  );
}

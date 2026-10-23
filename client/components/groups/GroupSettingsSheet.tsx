import { useState } from 'react';
import { ScrollView, View } from 'react-native';
import { useUpdateGroup } from '../../api/groups';
import { ApiError } from '../../lib/apiClient';
import type { ComfortCapDTO, GroupDetailDTO } from '../../types/api';
import { CertificationPicker } from '../comfort/CertificationPicker';
import { limitsForCertification } from '../comfort/comfortCopy';
import { ContentLimitsEditor } from '../comfort/ContentLevelPicker';
import { Body, Button, Label, Sheet, TextField, errorMessage } from '../ui';
import { ColorSwatches, EmojiPicker } from './GroupStylePickers';
import { groupColor, groupEmoji } from './groupStyle';
import { validateGroupForm } from './validateGroup';

type Props = {
  group: GroupDetailDTO;
  visible: boolean;
  onClose: () => void;
  onDelete: () => void;
  /** iOS: the sheet finished closing (see useSheetHandoff). */
  onDismiss?: () => void;
};

/**
 * Owner-only settings: rename, emoji, colour, and the group comfort cap (e.g. a "Kids" group capped
 * at PG). Mounted fresh each time it opens, so the draft starts from the current group.
 */
export function GroupSettingsSheet({ group, visible, onClose, onDelete, onDismiss }: Props) {
  return (
    <Sheet visible={visible} onClose={onClose} onDismiss={onDismiss} title="Group settings">
      {visible ? <SettingsForm group={group} onDone={onClose} onDelete={onDelete} /> : null}
    </Sheet>
  );
}

function SettingsForm({ group, onDone, onDelete }: { group: GroupDetailDTO; onDone: () => void; onDelete: () => void }) {
  const update = useUpdateGroup();
  const [name, setName] = useState(group.name);
  const [emoji, setEmoji] = useState(groupEmoji(group.emoji));
  const [color, setColor] = useState(groupColor(group.color));
  const [cap, setCap] = useState<ComfortCapDTO>(group.comfortCap);
  const [nameError, setNameError] = useState<string | null>(null);

  const save = () => {
    const errors = validateGroupForm(name, []);
    setNameError(errors.name ?? null);
    if (errors.name) return;
    update.mutate(
      { groupId: group.id, body: { name: name.trim(), emoji, color, comfortCap: { ...cap, contentLimits: limitsForCertification(cap.maxCertification, cap.contentLimits) } } },
      { onSuccess: onDone },
    );
  };

  const serverNameError = update.error instanceof ApiError ? update.error.fieldErrors.name : undefined;

  return (
    <ScrollView style={{ maxHeight: 560 }} keyboardShouldPersistTaps="handled">
      <View className="gap-5 pb-4">
        <TextField
          label="Group name"
          value={name}
          maxLength={60}
          onChangeText={(t) => {
            setName(t);
            setNameError(null);
          }}
          error={nameError ?? serverNameError ?? null}
        />
        <EmojiPicker value={emoji} onChange={setEmoji} />
        <ColorSwatches value={color} onChange={setColor} />
        <View className="gap-3">
          <Label accessibilityRole="header">Group comfort cap</Label>
          <Body muted className="text-sm">
            A ceiling for everyone in this group, on top of each person’s own settings — e.g. cap a kids’ group at PG.
          </Body>
          <CertificationPicker
            title="Highest rating for this group"
            value={cap.maxCertification}
            onChange={(maxCertification) =>
              setCap((c) => ({ maxCertification, contentLimits: limitsForCertification(maxCertification, c.contentLimits) }))
            }
          />
          <ContentLimitsEditor
            value={cap.contentLimits}
            maxCertification={cap.maxCertification}
            onChange={(contentLimits) => setCap((c) => ({ ...c, contentLimits }))}
          />
        </View>
        {update.isError && !serverNameError ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {errorMessage(update.error)}
          </Body>
        ) : null}
        <Button title="Save changes" loading={update.isPending} onPress={save} />
        <Button title="Delete group" variant="danger" disabled={update.isPending} onPress={onDelete} />
      </View>
    </ScrollView>
  );
}

import { useState } from 'react';
import { View } from 'react-native';
import { useAddSessionMembers } from '../../api/sessions';
import { pluralize } from '../../lib/format';
import type { SessionDTO } from '../../types/api';
import { FriendPicker } from '../groups/FriendPicker';
import { Body, Button, Sheet, errorMessage } from '../ui';

/** A session holds at most 20 people, like a group. */
export const MAX_SESSION_MEMBERS = 20;

/**
 * Adds some of your friends to an open session. They get the same email as the original invitees;
 * their comfort settings apply at once, so some picks may drop out for everyone.
 */
export function AddPeopleSheet({ session, visible, onClose }: { session: SessionDTO; visible: boolean; onClose: () => void }) {
  const add = useAddSessionMembers();
  const [selected, setSelected] = useState<string[]>([]);
  // memberCount, not members.length: the list leaves out people blocked either way with the viewer.
  const room = Math.max(0, MAX_SESSION_MEMBERS - session.memberCount);
  const close = () => {
    setSelected([]);
    add.reset();
    onClose();
  };
  return (
    <Sheet visible={visible} onClose={close} title="Add people">
      <View className="gap-4">
        {room === 0 ? (
          <Body muted>This session is full ({MAX_SESSION_MEMBERS} people).</Body>
        ) : (
          <>
            <Body muted className="text-sm">
              They can vote right away. Picks that don’t fit their comfort settings drop out for everyone.
            </Body>
            <FriendPicker
              selected={selected}
              onChange={setSelected}
              max={room}
              excludeIds={session.members.map((m) => m.user.id)}
            />
          </>
        )}
        {add.isError ? (
          <Body accessibilityRole="alert" className="text-sm text-danger">
            {errorMessage(add.error)}
          </Body>
        ) : null}
        <Button
          title={selected.length ? `Add ${pluralize(selected.length, 'friend')}` : 'Add'}
          disabled={selected.length === 0}
          loading={add.isPending}
          onPress={() => add.mutate({ sessionId: session.id, userIds: selected }, { onSuccess: close })}
        />
      </View>
    </Sheet>
  );
}

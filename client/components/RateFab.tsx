import { useState } from 'react';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Body, Button, Sheet, Slider1to10 } from './ui';
import { useTheme } from '../theme/useTheme';

type Props = { bottomOffset: number; rightOffset?: number };

/**
 * The floating "+ Rate" button shown over every tab. Opens the quick-rate sheet — a placeholder
 * until Phase 1b wires it to movie search and POST /ratings.
 */
export function RateFab({ bottomOffset, rightOffset = 20 }: Props) {
  const { colors } = useTheme();
  const [open, setOpen] = useState(false);
  const [score, setScore] = useState(7);

  return (
    <>
      <View pointerEvents="box-none" className="absolute" style={{ bottom: bottomOffset, right: rightOffset }}>
        <Pressable
          accessibilityRole="button"
          accessibilityLabel="Rate a movie"
          onPress={() => setOpen(true)}
          className="flex-row items-center gap-1.5 rounded-full bg-gold px-5 py-3.5 shadow-lg active:opacity-80"
        >
          <Ionicons name="add" size={22} color={colors.onGold} />
          <Text className="font-body-semibold text-base text-on-gold">Rate</Text>
        </Pressable>
      </View>
      <Sheet visible={open} onClose={() => setOpen(false)} title="Quick rate">
        <View className="gap-5">
          <Body muted>
            Search for what you watched and score it in one tap. Quick rate arrives in Phase 1b — here&apos;s the
            scale it will use.
          </Body>
          <Slider1to10 value={score} onChange={setScore} accessibilityLabel="Preview score" />
          <Button title="Close" variant="secondary" onPress={() => setOpen(false)} />
        </View>
      </Sheet>
    </>
  );
}

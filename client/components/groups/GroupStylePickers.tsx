import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Label } from '../ui';
import { GROUP_COLORS, GROUP_EMOJIS } from './groupStyle';

/** A simple grid of movie-ish emojis; one is selected. */
export function EmojiPicker({ value, onChange }: { value: string; onChange: (emoji: string) => void }) {
  return (
    <View className="gap-2">
      <Label accessibilityRole="header">Emoji</Label>
      <View accessibilityRole="radiogroup" accessibilityLabel="Emoji" className="flex-row flex-wrap gap-2">
        {GROUP_EMOJIS.map((e) => {
          const selected = e === value;
          return (
            <Pressable
              key={e}
              accessibilityRole="radio"
              accessibilityLabel={`Emoji ${e}`}
              accessibilityState={{ checked: selected }}
              onPress={() => onChange(e)}
              className={`h-11 w-11 items-center justify-center rounded-xl border active:opacity-70 ${
                selected ? 'border-accent bg-raised' : 'border-border bg-surface'
              }`}
            >
              <Text className="text-xl">{e}</Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

/** Round colour swatches from the Marquee-friendly palette. */
export function ColorSwatches({ value, onChange }: { value: string; onChange: (hex: string) => void }) {
  return (
    <View className="gap-2">
      <Label accessibilityRole="header">Colour</Label>
      <View accessibilityRole="radiogroup" accessibilityLabel="Colour" className="flex-row flex-wrap gap-3">
        {GROUP_COLORS.map((c) => {
          const selected = c.hex.toLowerCase() === value.toLowerCase();
          return (
            <Pressable
              key={c.hex}
              accessibilityRole="radio"
              accessibilityLabel={c.name}
              accessibilityState={{ checked: selected }}
              onPress={() => onChange(c.hex)}
              className={`h-10 w-10 items-center justify-center rounded-full border-2 active:opacity-70 ${
                selected ? 'border-text' : 'border-transparent'
              }`}
              style={{ backgroundColor: c.hex }}
            >
              {selected ? <Ionicons name="checkmark" size={20} color="#1B1622" /> : null}
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

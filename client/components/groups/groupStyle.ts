/** Movie-night emojis offered when creating or editing a group. */
export const GROUP_EMOJIS = [
  '🎬', '🍿', '🎥', '🎞️', '📽️', '🎟️', '🎭', '🌟',
  '🛋️', '🌙', '👻', '🦖', '🚀', '🤠', '🕵️', '🧙',
  '🦸', '💘', '😂', '😱', '🐉', '🏴‍☠️', '👽', '🎉',
] as const;

/** Swatches that sit well on the Marquee background (gold, velvet and friends). */
export const GROUP_COLORS = [
  { hex: '#F5B942', name: 'Marquee gold' },
  { hex: '#C7354A', name: 'Velvet red' },
  { hex: '#E8743B', name: 'Popcorn orange' },
  { hex: '#4CC38A', name: 'Ticket green' },
  { hex: '#3DA5D9', name: 'Neon blue' },
  { hex: '#8E6CEF', name: 'Twilight purple' },
  { hex: '#E86FA8', name: 'Premiere pink' },
  { hex: '#A39BB0', name: 'Projector grey' },
] as const;

export const DEFAULT_GROUP_EMOJI = GROUP_EMOJIS[0];
export const DEFAULT_GROUP_COLOR = GROUP_COLORS[0].hex;

const HEX = /^#[0-9A-Fa-f]{6}$/;

/** A group's accent colour, falling back to gold when unset or malformed. */
export function groupColor(color: string | null | undefined): string {
  return color && HEX.test(color) ? color : DEFAULT_GROUP_COLOR;
}

export function groupEmoji(emoji: string | null | undefined): string {
  return emoji?.trim() || DEFAULT_GROUP_EMOJI;
}

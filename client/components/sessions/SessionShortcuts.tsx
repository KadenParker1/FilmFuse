import { useState, type ReactNode } from 'react';
import { Pressable, Text, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Image } from 'expo-image';
import { useCreateGroup } from '../../api/groups';
import { useMovie } from '../../api/movies';
import type { SessionSetup } from '../../api/sessionSetup';
import { ApiError } from '../../lib/apiClient';
import { personName, pluralize } from '../../lib/format';
import { useTheme } from '../../theme/useTheme';
import type { GroupDetailDTO, SessionFiltersDTO } from '../../types/api';
import { AvatarStack } from '../groups/AvatarStack';
import { EmojiPicker } from '../groups/GroupStylePickers';
import { DEFAULT_GROUP_COLOR, DEFAULT_GROUP_EMOJI } from '../groups/groupStyle';
import { validateGroupForm } from '../groups/validateGroup';
import { Body, Button, Card, Label, TextField, errorMessage } from '../ui';
import type { SessionFilterState } from './SessionOptions';
import { RUNTIME_CHOICES, strategyLabel } from './sessionCopy';

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** The filter form's state for a past session's filters. */
export function filtersFromDTO(filters: SessionFiltersDTO): SessionFilterState {
  return {
    maxRuntime: filters.maxRuntime ?? null,
    fromYear: filters.minYear != null ? String(filters.minYear) : '',
    genreIds: filters.genreIds ?? [],
  };
}

/** Member ids from a `memberIds` route param ("a,b" or repeated): UUIDs only, deduplicated, at most `max`. */
export function parseMemberIdsParam(value: string | string[] | undefined, max: number): string[] {
  const raw = (Array.isArray(value) ? value.join(',') : (value ?? '')).split(',').map((s) => s.trim());
  return [...new Set(raw.filter((s) => UUID_RE.test(s)))].slice(0, max);
}

/** A positive whole movie id from a route param, or null. */
export function parseMovieIdParam(value: string | string[] | undefined): number | null {
  const raw = Array.isArray(value) ? value[0] : value;
  if (!raw || !/^\d{1,10}$/.test(raw)) return null;
  const id = Number(raw);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

/** "Under 2h · from 1990 · 2 genres" — empty when no filter is set. */
export function describeFilters(filters: SessionFiltersDTO): string {
  const parts: string[] = [];
  if (filters.maxRuntime != null) {
    parts.push(RUNTIME_CHOICES.find((c) => c.value === filters.maxRuntime)?.label ?? `Under ${filters.maxRuntime} min`);
  }
  if (filters.minYear != null) parts.push(`from ${filters.minYear}`);
  if (filters.genreIds?.length) parts.push(pluralize(filters.genreIds.length, 'genre'));
  return parts.join(' · ');
}

/** "Same crew again": the last session's people and options, filled in with one tap. */
export function SameCrewCard({
  setup,
  includeMembers,
  onUse,
}: {
  setup: SessionSetup;
  /** Tonight shows (and reuses) the people; a group already has its members. */
  includeMembers: boolean;
  onUse: () => void;
}) {
  const { colors } = useTheme();
  const filters = describeFilters(setup.filters);
  const names = setup.members.slice(0, 3).map(personName).join(', ');
  const more = setup.members.length > 3 ? ` +${setup.members.length - 3}` : '';
  const summary = [includeMembers ? `${names}${more}` : null, filters || null, strategyLabel(setup.strategy)]
    .filter(Boolean)
    .join(' · ');
  return (
    <Pressable
      testID="same-crew"
      accessibilityRole="button"
      accessibilityLabel={`Same crew again: ${summary}`}
      accessibilityHint="Fills in the people and options from your last session"
      onPress={onUse}
      className="flex-row items-center gap-3 rounded-2xl border border-border bg-surface px-4 py-3 active:opacity-80"
    >
      <Ionicons name="repeat" size={20} color={colors.accentInk} />
      <View className="flex-1 gap-1">
        <Text className="font-body-semibold text-base text-text">
          {includeMembers ? 'Same crew again' : 'Same as last time'}
        </Text>
        <Text className="font-body text-sm text-muted" numberOfLines={2}>
          {summary}
        </Text>
      </View>
      {includeMembers && setup.members.length ? (
        <AvatarStack users={setup.members} total={setup.members.length} />
      ) : null}
    </Pressable>
  );
}

/**
 * The movie a session was started from ("Watch with…"). Sessions can't be seeded with a movie, so
 * it's shown as context only — the picks still come from everyone's taste.
 */
export function MovieContextCard({ movieId }: { movieId: number }) {
  const movie = useMovie(movieId);
  if (!movie.data) return null;
  return (
    <Card testID="session-movie-context" className="flex-row items-center gap-3">
      <View className="h-16 w-11 overflow-hidden rounded-lg bg-raised">
        {movie.data.posterUrl ? (
          <Image
            source={{ uri: movie.data.posterUrl }}
            accessibilityLabel={`${movie.data.title} poster`}
            style={{ width: '100%', height: '100%' }}
            contentFit="cover"
          />
        ) : null}
      </View>
      <View className="flex-1 gap-0.5">
        <Text className="font-body-semibold text-base text-text" numberOfLines={1}>
          Thinking of {movie.data.title}?
        </Text>
        <Body muted className="text-sm">
          Pick who’s watching. FilmFuse ranks picks for everyone, so bring it up when you vote.
        </Body>
      </View>
    </Card>
  );
}

/** A collapsible section, closed by default. */
export function Collapsible({ title, summary, children }: { title: string; summary?: string; children: ReactNode }) {
  const { colors } = useTheme();
  const [open, setOpen] = useState(false);
  return (
    <View className="gap-3">
      <Pressable
        accessibilityRole="button"
        accessibilityLabel={summary ? `${title}, ${summary}` : title}
        accessibilityState={{ expanded: open }}
        onPress={() => setOpen((o) => !o)}
        className="flex-row items-center gap-2 self-start py-1 active:opacity-70"
      >
        <Ionicons name={open ? 'chevron-down' : 'chevron-forward'} size={18} color={colors.muted} />
        <Label>{title}</Label>
        {summary && !open ? <Text className="font-body text-sm text-muted">· {summary}</Text> : null}
      </Pressable>
      {open ? children : null}
    </View>
  );
}

/** The form behind "Save these people as a group": a name and an emoji. */
function SaveCrewForm({
  memberIds,
  onSaved,
  onCancel,
}: {
  memberIds: string[];
  onSaved: (group: GroupDetailDTO) => void;
  onCancel: () => void;
}) {
  const create = useCreateGroup();
  const [name, setName] = useState('');
  const [emoji, setEmoji] = useState<string>(DEFAULT_GROUP_EMOJI);
  const [nameError, setNameError] = useState<string | null>(null);

  const save = () => {
    const errors = validateGroupForm(name, memberIds);
    setNameError(errors.name ?? errors.members ?? null);
    if (errors.name || errors.members) return;
    create.mutate({ name: name.trim(), emoji, color: DEFAULT_GROUP_COLOR, memberIds }, { onSuccess: onSaved });
  };

  const fieldErrors = create.error instanceof ApiError ? create.error.fieldErrors : {};
  const shownNameError = nameError ?? fieldErrors.name ?? null;
  return (
    <Card testID="save-crew-form" className="gap-4">
      <TextField
        label="Group name"
        placeholder="Friday film club"
        value={name}
        maxLength={60}
        onChangeText={(t) => {
          setName(t);
          setNameError(null);
        }}
        error={shownNameError}
      />
      <EmojiPicker value={emoji} onChange={setEmoji} />
      <Body muted className="text-sm">
        {pluralize(memberIds.length, 'friend')} will get an invite to join.
      </Body>
      {create.isError && !fieldErrors.name ? (
        <Body accessibilityRole="alert" className="text-sm text-danger">
          {errorMessage(create.error)}
        </Body>
      ) : null}
      <View className="flex-row gap-3">
        <Button title="Cancel" variant="ghost" className="flex-1" disabled={create.isPending} onPress={onCancel} />
        <Button title="Save group" className="flex-1" loading={create.isPending} onPress={save} />
      </View>
    </Card>
  );
}

/**
 * "Save these people as a group" under the Tonight friend picker: keeps the crew as a saved group
 * (POST /groups; the friends are invited). Tonight's session itself stays ad hoc.
 */
export function SaveCrewAsGroup({ memberIds }: { memberIds: string[] }) {
  const { colors } = useTheme();
  const [open, setOpen] = useState(false);
  // Remembers who was saved: picking different people offers saving again.
  const [saved, setSaved] = useState<{ group: GroupDetailDTO; memberKey: string } | null>(null);
  const memberKey = [...memberIds].sort().join(',');

  if (saved && saved.memberKey === memberKey) {
    return (
      <View testID="save-crew-done" accessibilityLiveRegion="polite" className="flex-row items-center gap-2">
        <Ionicons name="checkmark-circle" size={18} color={colors.success} />
        <Body className="text-sm">
          Saved as {saved.group.emoji ?? DEFAULT_GROUP_EMOJI} {saved.group.name}. Invites are on their way.
        </Body>
      </View>
    );
  }
  if (memberIds.length === 0) return null;
  if (open) {
    return (
      <SaveCrewForm
        memberIds={memberIds}
        onCancel={() => setOpen(false)}
        onSaved={(group) => {
          setSaved({ group, memberKey });
          setOpen(false);
        }}
      />
    );
  }
  return (
    <Button
      size="sm"
      variant="ghost"
      title="Save these people as a group"
      icon={<Ionicons name="people-outline" size={16} color={colors.accentInk} />}
      className="self-start"
      onPress={() => setOpen(true)}
    />
  );
}

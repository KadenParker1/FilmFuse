import type { Certification, ComfortSettingsDTO, ContentDimension, ContentLevel } from '../../types/api';

/** Least → most mature, matching the server's ordering. */
export const CERTIFICATIONS: Certification[] = ['G', 'PG', 'PG_13', 'R', 'NC_17'];

export const CONTENT_LEVELS: ContentLevel[] = ['NONE', 'MILD', 'MODERATE', 'STRONG'];

export const CONTENT_DIMENSIONS: ContentDimension[] = ['VIOLENCE', 'SEXUAL', 'LANGUAGE', 'DRUGS', 'FRIGHTENING'];

/** "PG_13" → "PG-13". */
export function certificationLabel(cert: Certification): string {
  return cert.replace('_', '-');
}

/** Plain-language meaning of a maximum rating; null = no limit. */
export function certificationDescription(cert: Certification | null): string {
  switch (cert) {
    case null:
      return 'Any rating — nothing is filtered out by its rating.';
    case 'G':
      return 'General audiences only: suitable for all ages.';
    case 'PG':
      return 'Up to PG: some material may not suit young children.';
    case 'PG_13':
      return 'Up to PG-13: some material may be inappropriate under 13.';
    case 'R':
      return 'Up to R: adult themes, strong language or violence.';
    case 'NC_17':
      return 'Up to NC-17: adults only, the most explicit films.';
  }
}

export const LEVEL_LABELS: Record<ContentLevel, string> = {
  NONE: 'None',
  MILD: 'Mild',
  MODERATE: 'Moderate',
  STRONG: 'Strong',
};

export const DIMENSION_LABELS: Record<ContentDimension, string> = {
  VIOLENCE: 'Violence & gore',
  SEXUAL: 'Sex & nudity',
  LANGUAGE: 'Language',
  DRUGS: 'Drug use',
  FRIGHTENING: 'Frightening & intense',
};

/** What each ceiling lets through, per dimension. */
export const LEVEL_EXAMPLES: Record<ContentDimension, Record<ContentLevel, string>> = {
  VIOLENCE: {
    NONE: 'No fighting or injury on screen.',
    MILD: 'Cartoon or slapstick scuffles, no blood.',
    MODERATE: 'Action fights and some blood, not lingered on.',
    STRONG: 'Anything, including graphic gore.',
  },
  SEXUAL: {
    NONE: 'Nothing beyond holding hands.',
    MILD: 'Kissing and innuendo.',
    MODERATE: 'Implied sex scenes, brief nudity.',
    STRONG: 'Anything, including explicit scenes.',
  },
  LANGUAGE: {
    NONE: 'No swearing at all.',
    MILD: 'The odd "damn" or "hell".',
    MODERATE: 'Regular swearing, the occasional f-word.',
    STRONG: 'Anything goes.',
  },
  DRUGS: {
    NONE: 'No drinking, smoking or drug use.',
    MILD: 'Social drinking or smoking in the background.',
    MODERATE: 'Characters using drugs, not dwelt on.',
    STRONG: 'Anything, including heavy drug use.',
  },
  FRIGHTENING: {
    NONE: 'Gentle — nothing scary.',
    MILD: 'Brief tense moments, quickly resolved.',
    MODERATE: 'Sustained suspense and some jump scares.',
    STRONG: 'Anything, including full-on horror.',
  },
};

/** A missing dimension means no limit. */
export const levelFor = (limits: Partial<Record<ContentDimension, ContentLevel>>, dim: ContentDimension): ContentLevel =>
  limits[dim] ?? 'STRONG';

/**
 * Sets one dimension. STRONG is the same as no limit, so it's stored by leaving the key out — the
 * payload only ever lists real limits.
 */
export function withLevel(
  limits: Partial<Record<ContentDimension, ContentLevel>>,
  dim: ContentDimension,
  level: ContentLevel,
): Partial<Record<ContentDimension, ContentLevel>> {
  const next = { ...limits };
  if (level === 'STRONG') delete next[dim];
  else next[dim] = level;
  return next;
}

/** True when two limit maps mean the same thing (missing ≡ STRONG). */
export function sameLimits(
  a: Partial<Record<ContentDimension, ContentLevel>>,
  b: Partial<Record<ContentDimension, ContentLevel>>,
): boolean {
  return CONTENT_DIMENSIONS.every((d) => levelFor(a, d) === levelFor(b, d));
}

export const GROUP_PRIVACY_NOTE =
  'In groups, FilmFuse uses the most cautious settings of everyone present. Nobody sees whose settings set the limit.';

/** Client-side checks before PUT; returns a message or null. */
export function validateComfort(settings: ComfortSettingsDTO): string | null {
  if (settings.maxCertification !== null && !CERTIFICATIONS.includes(settings.maxCertification)) {
    return 'Pick a rating from the list.';
  }
  const unknown = Object.entries(settings.contentLimits).some(
    ([dim, level]) =>
      !CONTENT_DIMENSIONS.includes(dim as ContentDimension) || !CONTENT_LEVELS.includes(level as ContentLevel),
  );
  if (unknown) return 'One of the content limits isn’t valid. Pick None, Mild, Moderate or Strong.';
  return null;
}

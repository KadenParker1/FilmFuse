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

/**
 * The gentlest selectable limit per dimension. The content tagger never labels a film "none" for
 * violence or frightening (even G films carry cartoon peril), so "None" there matched almost nothing
 * and emptied group sessions: those two start at Mild. The server treats a NONE for them as MILD.
 */
export const MIN_LEVEL: Record<ContentDimension, ContentLevel> = {
  VIOLENCE: 'MILD',
  SEXUAL: 'NONE',
  LANGUAGE: 'NONE',
  DRUGS: 'NONE',
  FRIGHTENING: 'MILD',
};

/** The levels a picker offers for this dimension, gentlest first. Single source of truth for level lists. */
export function levelsFor(dim: ContentDimension): ContentLevel[] {
  return CONTENT_LEVELS.slice(CONTENT_LEVELS.indexOf(MIN_LEVEL[dim]));
}

/** Raises a level below the dimension's minimum to that minimum (e.g. an old NONE for violence → MILD). */
export function clampLevel(dim: ContentDimension, level: ContentLevel): ContentLevel {
  return CONTENT_LEVELS.indexOf(level) < CONTENT_LEVELS.indexOf(MIN_LEVEL[dim]) ? MIN_LEVEL[dim] : level;
}

/**
 * A limits map with every level selectable: older values the pickers no longer offer (NONE for
 * violence / frightening, from a cached response or an old draft) become the minimum. Unknown keys and
 * values pass through untouched for validateComfort to report.
 */
export function normalizeLimits(
  limits: Partial<Record<ContentDimension, ContentLevel>>,
): Partial<Record<ContentDimension, ContentLevel>> {
  const next = { ...limits };
  for (const dim of CONTENT_DIMENSIONS) {
    const level = next[dim];
    if (level !== undefined && CONTENT_LEVELS.includes(level)) next[dim] = clampLevel(dim, level);
  }
  return next;
}

type Examples = Record<ContentLevel, string>;
/** Dimensions that start at Mild have no "None" example. */
type FromMild = Omit<Examples, 'NONE'>;

/** What each ceiling lets through, per dimension. Read through `levelExample`. */
export const LEVEL_EXAMPLES: {
  VIOLENCE: FromMild;
  SEXUAL: Examples;
  LANGUAGE: Examples;
  DRUGS: Examples;
  FRIGHTENING: FromMild;
} = {
  VIOLENCE: {
    MILD: 'Mild — the gentlest setting: cartoon or slapstick scuffles, no blood.',
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
    MILD: 'Mild — the gentlest setting: brief tense moments or cartoon peril, quickly resolved.',
    MODERATE: 'Sustained suspense and some jump scares.',
    STRONG: 'Anything, including full-on horror.',
  },
};

/** The example for a level, clamped to what the dimension offers. */
export function levelExample(dim: ContentDimension, level: ContentLevel): string {
  const examples: Partial<Examples> = LEVEL_EXAMPLES[dim];
  return examples[clampLevel(dim, level)] ?? '';
}

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

/**
 * Below R (G / PG / PG-13) the rating already rules out sexual content, so the "Sex & nudity" row is
 * hidden. It shows for R, NC-17 and No limit (null).
 */
export function showsSexualRow(cert: Certification | null): boolean {
  return cert === null || cert === 'R' || cert === 'NC_17';
}

/** The dimensions that have a picker under this maximum rating. */
export function visibleDimensions(cert: Certification | null): ContentDimension[] {
  return showsSexualRow(cert) ? CONTENT_DIMENSIONS : CONTENT_DIMENSIONS.filter((d) => d !== 'SEXUAL');
}

/**
 * The limits to save under this maximum rating: a hidden row's value is never sent, so SEXUAL is
 * dropped below R (and comes back at its default, Strong / no limit, when the rating goes up again).
 */
export function limitsForCertification(
  cert: Certification | null,
  limits: Partial<Record<ContentDimension, ContentLevel>>,
): Partial<Record<ContentDimension, ContentLevel>> {
  if (showsSexualRow(cert) || limits.SEXUAL === undefined) return limits;
  const next = { ...limits };
  delete next.SEXUAL;
  return next;
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
  if (unknown) return 'One of the content limits isn’t valid. Pick one of the listed levels.';
  return null;
}

/**
 * Mirrors core-api's `@SafeText` (SECURITY.md #19) so forms can flag a problem before sending:
 * no C0/C1 control characters (a line feed is fine in multi-line text), no bidi embeddings,
 * overrides or isolates (U+202A–U+202E, U+2066–U+2069), and no zero-width character at either end.
 * The server stays the authority; its field error is shown if this ever disagrees.
 */
export const UNSAFE_TEXT_MESSAGE = 'Cannot contain control characters or invisible formatting characters';

// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0009\u000B-\u001F\u007F-\u009F]/;
const BIDI = /[‪-‮⁦-⁩]/;
const EDGE_ZERO_WIDTH = /^[​-‍⁠﻿]|[​-‍⁠﻿]$/;

export function isSafeText(value: string, multiline = false): boolean {
  if (CONTROL.test(value) || BIDI.test(value) || EDGE_ZERO_WIDTH.test(value)) return false;
  return multiline || !value.includes('\n');
}

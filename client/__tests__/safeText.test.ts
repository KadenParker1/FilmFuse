import { UNSAFE_TEXT_MESSAGE, isSafeText } from '../lib/safeText';

describe('isSafeText (mirrors core-api @SafeText)', () => {
  it.each(['', 'Ada Lovelace', 'Zoë مرحبا 名前', 'Family 👨‍👩‍👧', 'no-break space'])(
    'accepts %p',
    (value) => {
      expect(isSafeText(value)).toBe(true);
      expect(isSafeText(value, true)).toBe(true);
    },
  );

  it.each([
    'a\u0000b',
    'a\tb',
    'a\rb',
    'a\u007Fb',
    'a\u0085b',
    'evil‮txt',
    'a‪b',
    'a⁦b',
    'a⁩b',
    '​Ada',
    'Ada‍',
    '⁠Ada',
    'Ada﻿',
  ])('rejects %p in both modes', (value) => {
    expect(isSafeText(value)).toBe(false);
    expect(isSafeText(value, true)).toBe(false);
  });

  it('allows line feeds only in multi-line text', () => {
    expect(isSafeText('one\ntwo', true)).toBe(true);
    expect(isSafeText('one\ntwo')).toBe(false);
  });

  it('uses the server’s wording', () => {
    expect(UNSAFE_TEXT_MESSAGE).toBe('Cannot contain control characters or invisible formatting characters');
  });
});

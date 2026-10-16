/**
 * Native: Supabase keeps its session in the OS keychain/keystore via expo-secure-store.
 *
 * SecureStore warns above ~2 KB per value and a Supabase session (JWT + refresh token + user) can
 * exceed that, so values are split into chunks: `<key>.count` holds the chunk count and
 * `<key>.0..n` the pieces.
 */
import 'react-native-url-polyfill/auto';
import * as SecureStore from 'expo-secure-store';
import type { SupportedStorage } from '@supabase/supabase-js';

const CHUNK_SIZE = 1800;
const countKey = (key: string) => `${key}.count`;
const chunkKey = (key: string, i: number) => `${key}.${i}`;

async function removeChunks(key: string): Promise<void> {
  const count = Number((await SecureStore.getItemAsync(countKey(key))) ?? 0);
  await Promise.all(Array.from({ length: count }, (_, i) => SecureStore.deleteItemAsync(chunkKey(key, i))));
  await SecureStore.deleteItemAsync(countKey(key));
}

export const authStorage: SupportedStorage = {
  async getItem(key) {
    const raw = await SecureStore.getItemAsync(countKey(key));
    if (raw === null) return null;
    const chunks = await Promise.all(
      Array.from({ length: Number(raw) }, (_, i) => SecureStore.getItemAsync(chunkKey(key, i))),
    );
    if (chunks.some((c) => c === null)) return null;
    return chunks.join('');
  },
  async setItem(key, value) {
    await removeChunks(key);
    const count = Math.max(1, Math.ceil(value.length / CHUNK_SIZE));
    for (let i = 0; i < count; i++) {
      await SecureStore.setItemAsync(chunkKey(key, i), value.slice(i * CHUNK_SIZE, (i + 1) * CHUNK_SIZE));
    }
    await SecureStore.setItemAsync(countKey(key), String(count));
  },
  async removeItem(key) {
    await removeChunks(key);
  },
};

/** Native has no URL bar; deep-link auth callbacks are handled explicitly (Phase 1a). */
export const detectSessionInUrl = false;

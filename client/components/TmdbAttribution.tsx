import { Body } from './ui';

export const TMDB_ATTRIBUTION = 'This product uses the TMDB API but is not endorsed or certified by TMDB.';

/** Required by TMDB's terms wherever their data is shown. */
export function TmdbAttribution() {
  return (
    <Body muted className="px-2 pb-4 pt-6 text-center text-xs">
      {TMDB_ATTRIBUTION}
    </Body>
  );
}

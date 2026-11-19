# Persona file schema (`tools/personas/personas.json`)

Test personas for seeding the local FilmFuse stack with realistic, varied accounts. Local only.
Everything is created through the real APIs (Supabase Auth + core-api), never by writing to the database.

```jsonc
{
  "version": 1,
  "password": "ff-persona-local-1",          // shared local-only password for every persona account
  "emailDomain": "filmfuse.test",            // email = <key>@<emailDomain>
  "personas": [
    {
      "key": "disney-mom",                   // unique, kebab-case, also the email local part
      "username": "magic_kingdom_meg",       // unique, 3-30 chars, [a-z0-9_]
      "displayName": "Meg Alvarez",
      "archetype": "Disney mom",             // short label shown in the CSV
      "bio": "Two kids under 8, annual passholder, cries at every Pixar short.",
      "tags": ["family", "animation", "multi-genre"],   // free-form, used for coverage checks
      "genres": { "16": 10, "10751": 10, "10402": 8, "12": 7, "35": 7, "27": 1 },
                                              // TMDB genre id (string key) -> 1-10. Onboarding needs
                                              // 5+ entries with 3+ of them >= 8. Ids must be in GENRE_IDS.
      "moods": { "feel-good": 10, "cozy": 9, "tearjerker": 8, "dark-gritty": 1 },
                                              // MOOD_TAGS only, 1-10. May be empty.
      "favorites": [                          // 3-10, ordered (rank 1 first). Favourites are onboarding picks.
        { "title": "Moana", "year": 2016 }
      ],
      "anchorRatings": [                      // hand-picked ratings that define the persona (0-40)
        { "title": "Frozen", "year": 2013, "score": 9 },
        { "title": "Hereditary", "year": 2018, "score": 2 }
      ],
      "leastFavorites": [                     // optional, 0-5, ordered (rank 1 = most disliked).
        { "title": "Hereditary", "year": 2018 }   // Seeded via PUT /me/preferences/least-favorites.
      ],                                      // Never a favourite or an anchor love (>= 8); an anchor
                                              // for the same movie must be <= 3. Without an anchor,
                                              // its MovieLens genres must average <= 5 on this
                                              // persona's genre scores (override titles need the anchor).
      "volume": "medium",                     // how many generated ratings on top of anchors:
                                              //   none = 0, light = 10, medium = 40, heavy = 120, power = 300
      "rater": { "bias": 0.5, "spread": 1.0 },// generated-score style: bias shifts the mean (-3..+3,
                                              // + = generous), spread scales variance (0.3..2.0)
      "era": { "from": 1990, "to": 2026 },    // optional: generated ratings prefer this release range
      "popularity": "mainstream",             // mainstream | mixed | niche: generated ratings' popularity skew
      "comfort": {                            // optional; omitted = no limits. Same shape as PUT /me/settings/comfort
        "maxCertification": "PG",             // G | PG | PG_13 | R | NC_17 | null
        "contentLimits": { "VIOLENCE": "MILD", "FRIGHTENING": "MILD" },
        "allowUnrated": false
      },
      "onboarded": true                       // false = account exists but never finishes onboarding
    }
  ]
}
```

Tags with a meaning: `multi-genre` puts a persona in the cross-genre section of ROSTER.md;
`narrow` (always with `multi-genre`) marks someone who loves exactly 2-3 genres that rarely go
together and scores most other genres 1-3 (they carry least favourites); `edge-case` forces the
behaviour section.

Title resolution: every `{title, year}` (favourites, anchors and least favourites) is resolved to a
TMDB id by `resolve.py`, first through the local MovieLens `ml-latest-small` (`movies.csv` + `links.csv`, covers releases up to 2018), then through
`tmdb-overrides.json` (`{"<title> (<year>)": <tmdbId>}`), which holds movies after 2018 and must be
hand-verified against `https://www.themoviedb.org/movie/<id>` (title and year). The output is
the committed `personas.resolved.json`, which the seeder reads.
An unresolved title is an error.

Generated ratings come from MovieLens movies (deterministic per persona key, seeded). Each movie's
score comes from the persona's genre affinities, `rater`, `era` and `popularity`. Anchors, favourites
and least favourites are never overwritten (generated ratings skip them). This is test-data generation, not the recommendation algorithm.

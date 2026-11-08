# Backlog

Ideas and features agreed in conversation but not yet scheduled into a phase. The newest are at the top.
Roadmap phases: `~/.claude/plans/humble-waddling-cray.md`.

---

## Viewer archetypes: "what kind of movie person is this?" + audience mix in admin
*Added 2026-11-08 by the owner.*

**Idea.** Every user is evaluated from how they actually use FilmFuse, e.g. "Disney mom", "Film bro" or "Horror
fanatic + rom-com", and the profile is kept up to date as they keep rating. The admin dashboard shows the mix of
users across the platform.

**Ownership split** (see CLAUDE.md):
- **Owner's:** the classifier itself, i.e. what an archetype is and how a user is mapped to one or more. It reads
  taste vectors and ratings, so it belongs with the recommendation work in `python-ml-service/`.
- **Claude's:** the plumbing, triggers, storage, admin UI and evaluation. A clearly marked placeholder classifier
  (e.g. labels from the top stated/learned genres) keeps it running until the owner's version lands.

**Sketch (to be planned properly):**
- **Trigger: every 10 new ratings.** Keep a per-user `ratings_since_archetype` count, or compare the rating count
  with the count at the last evaluation. Reaching 10 marks the user for re-evaluation. Onboarding completion also
  triggers it, so new users get an initial label. A slow nightly sweep catches anyone missed, e.g. after a
  classifier version bump. This reuses the dirty-flag + scheduler pattern of `VectorRefreshScheduler`.
- **ML contract addition** (flag before changing the frozen contract): `POST /internal/archetypes` takes the
  user's vector, rating summary and stated prefs, and returns
  `{labels: [{archetype, confidence}], traits: {...}, modelVersion}`.
  - **Multi-label:** people are mixes. The persona sweep has Disney + horror, anime + western, and others.
  - **Traits are separate from taste labels** and are plain statistics, so core-api can compute them and they
    don't need the classifier:
    - rater style: generous / harsh / flat / normal, from the mean and spread of their scores
    - volume: light → power
    - mainstream vs niche: mean popularity of what they rate
    - era lean
- **Storage:** `user_archetypes` (current) + `user_archetype_history` (one row per evaluation, with
  `model_version` and the rating count at evaluation), like `user_interest_vector_history`.
  - `ml_reader` gets read access only if the classifier needs earlier labels.
- **Admin dashboard: "Audience mix" on `/admin`**, aggregate only:
  - share of users per archetype
  - share of multi-label users
  - rater-style and volume distribution
  - change over time from the history table
  - number of users not yet evaluated
  - **Privacy:** hide buckets with fewer than ~5 users, so a small archetype can't identify a person. Showing
    individual users' labels to admins is undecided (see questions).
- **Evaluation:** the persona sweep in `tools/personas/` is labelled ground truth. Each seeded persona has a known
  archetype, multi-genre tags and rater style. Add a check that runs the classifier over the seeded accounts and
  reports a confusion matrix: does the Disney + horror mom come out as both? Does the flat-six rater come out
  as "flat"? Re-run it after 10, 20 and 40 more generated ratings to see whether labels stabilise.

**Open questions for the owner:**
1. Does the user see their own archetype ("You're a Cozy Cinephile"), and can they correct it? A correction
   would be a strong extra signal.
2. Can admins see one user's label, or only aggregates?
3. A fixed taxonomy (e.g. the ~35 persona archetypes), or clusters learned from the data and named afterwards?
4. Is "every 10 ratings" counted in quick-rates only, or in every taste signal (calibration swipes, dismissals,
   session picks)?
5. Should archetypes feed back into recommendations (cold start, group explanations like "two horror fans and a
   Pixar crier"), or stay analytics only?

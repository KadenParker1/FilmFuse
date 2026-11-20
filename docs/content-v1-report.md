# FilmFuse recommender report: content-v1 → hybrid-v1

*2026-11-18. The model behind FilmFuse's personal and group recommendations, replacing the placeholder. The live model is now **hybrid-v1**: content-v1 plus item-kNN collaborative filtering (see the update at the end).*

## Outcome

- **Groups:** across the 40 persona groups, the share of picks that the least-happy member enjoys went from **7% to 36–42%**, with **0 comfort violations**.
- **Personal ("For you"):** results now follow each persona's taste.
- **Offline (MovieLens):** content-v1 **ties the popularity baseline** on NDCG@10 and **beats it on hit rate**.
- **Tests:** all suites pass.
- **Contract:** unchanged.
- **Status:** nothing is committed yet.

## What was built

The model is `python-ml-service/app/services/`, following Phase 0 of `docs/recommender-directions.md`.

| Part | What it does |
|---|---|
| `features.py` | Gives each movie a 27-number profile: its 19 genres, plus 8 moods worked out from genres and TMDB keywords. For example, "time travel" makes a film more *mind-bending*, and "christmas" makes it more *cozy*. The rules are a plain table you can edit. |
| `catalog.py` | Reads the movie catalog (~74k films) and each user's loved, disliked and dismissed movies through the read-only `ml_reader` database role. The catalog loads at startup and refreshes every 6 hours in the background. If the database is down, the model still answers (in core-api's order). |
| User vector | Each of the 27 taste values starts at what the user said in onboarding, then moves towards how they actually rated movies with that feature. Favourites and least favourites count 3× a rating, dismissals count a little, and old ratings fade. |
| Predicted score (1–10) | Taste match, plus similarity to movies they loved (minus disliked ones), plus a quality/popularity nudge. The nudge is stronger for brand-new users. **One hated genre sinks a film**: for a horror-hater, a "family horror" film scores low even though it's also family. |
| For you | Highest scores, with a light shuffle so the top 20 aren't all one genre. |
| Groups (`group_strategies.py`) | All five strategies: **mean**; **least misery** (best worst-case); **most pleasure**; **closeness-weighted** (close friends compromise like *mean*, loose groups protect the least-happy member like *least misery*); and **fairness**, a GFAR-style list that takes turns serving each member's own top picks. |
| Explanations | Each one names the biggest real reason behind the score, for example "Because you love Horror and dark, gritty films" or "Like The Conjuring, which you loved". Group explanations never single anyone out, for example "Everyone's into Comedy" or "Nobody's likely to hate it". |
| Calibration cards | Well-known films people have likely seen, rotating through genres and starting with the ones the user has rated least. |

**One core-api change (flagged):** the candidate pool sent to the model grew from **200 to 1000** movies, so niche tastes have something to choose from. The contract is unchanged.

## Results

### Live: 300 personas, 40 groups

These scores use each persona's *stated* genre scores, not real enjoyment, so they're a diagnostic. "Worst member enjoys" is the share of the 20 picks the least-happy member would rate 7 or more on their stated genres.

| Run | Mean fit | Worst member's average fit | Worst member enjoys | Comfort violations |
|---|---|---|---|---|
| Placeholder (before) | 5.7 | 4.4 | **6.9%** | 0 |
| content-v1, mean | 7.4 | 6.0 | **39.2%** | 0 |
| content-v1, least misery | 7.2 | **6.1** | 35.9% | 0 |
| content-v1, fairness | 7.2 | 5.7 | **42.1%** | 0 |

The same metric, worst member enjoys, broken down by kind of group:

| Group kind | Placeholder | Mean | Least misery | Fairness |
|---|---|---|---|---|
| Easy (shared taste), 12 groups | 12% | **75%** | 68% | 70% |
| Pairs, 7 | 11% | 64% | **69%** | 67% |
| Hard (clashing taste), 15 | 3% | 15% | 9% | **21%** |
| Big (8 people), 6 | 3% | 0% | 1% | **10%** |

36 of 40 groups improved. **Fairness helps hard and big groups most**, as designed. Least misery gives the best worst-case fit but finds fewer outright favourites.

Full report: `tools/personas/out/session-report-content-v1.md` (before: `session-report-300.md`).

### Spot checks: personal "For you"

| Persona | Top picks | Explanation shown |
|---|---|---|
| Disney mom | *Spirited Away*, *Totoro*, *Finding Nemo*, *Puss in Boots: The Last Wish* | "Because you love Animation and cozy films" |
| Horror fanatic | *The Shining*, *The Conjuring 2*, *Evil Dead Burn* | "Because you love Horror and dark, gritty films" |
| Horror + family (and nothing else) | *Spirited Away*, *The Exorcist*, *The Shining* (both of their genres) | Names whichever side applies |
| Film bro | *The Godfather* I and II, *Memento*, *Shutter Island* | "Because you love Crime and dark, gritty films" |
| Nollywood fan | *12 Angry Men*, *The Intouchables*, *Casablanca* | Drama, but **no Nollywood films** (see limits) |

### Offline: MovieLens harness

Command: `python -m eval.run`, 200 users, 100 groups per construction method, k = 10. Report: `python-ml-service/eval/reports/20261118-content-v1.md`.

**Personal recommendations:**

| | NDCG@10 | Hit rate@10 | Precision@10 | Catalog coverage |
|---|---|---|---|---|
| **content-v1** | 0.0775 | **0.390** | **0.068** | **1.16%** |
| Popularity baseline | **0.0778** | 0.345 | 0.061 | 0.77% |
| Random baseline | 0.0011 | 0.005 | 0.001 | 18.6% |

**Groups:** mean satisfaction of the members / satisfaction of the worst-off member, at k = 10.

| Group type | Popularity | content-v1 mean | least misery | closeness | fairness |
|---|---|---|---|---|---|
| Similar members | 0.046 / 0.003 | 0.059 / 0.007 | 0.056 / 0.007 | **0.061 / 0.008** | **0.061** / 0.008 |
| Random members | **0.057** / 0.003 | 0.053 / 0.003 | 0.050 / 0.004 | 0.052 / **0.004** | 0.050 / 0.003 |
| Dissimilar members | 0.035 / 0.002 | 0.032 / 0.003 | **0.036 / 0.005** | 0.032 / 0.004 | 0.035 / 0.003 |

- **Contract violations:** none.
- **Speed:** about 46 ms per personal call and 137 ms per group call, on a 9,700-movie pool. Live calls on 1000-movie pools are faster.
- **Groups versus popularity:** content-v1 beats it clearly for similar groups. For dissimilar groups, least misery gives the worst-off member more than popularity does (0.005 vs 0.002).

**Why content-v1 doesn't clearly beat popularity offline:**
- On MovieLens, "most popular" is a famously strong baseline. Genre-level taste alone can't out-predict it; that needs collaborative filtering, which is Phase 2 in the plan.
- I tuned the popularity/quality nudge as a trade-off. Leaning harder on popularity raised offline NDCG to about 0.083, but it gave the Disney mom *The Dark Knight*.
- The chosen setting pushes obscure films down hard, and among well-known films taste decides. It ties popularity on NDCG@10, beats it on hit rate, and recommends a wider range of films.
- In the live app core-api already sends well-known films, so taste does the deciding there.

## Bugs found and fixed along the way

1. **Scores capped before ranking.** Clamping to 10 before sorting tied every strong movie at 10, so their order became random. The model now ranks on raw scores and caps only the numbers it returns.
2. **The cold-start weight ran backwards.** The quality/popularity nudge grew as we learned about a user instead of shrinking.
3. **Fairness behaved exactly like mean offline.** It measured "liking" in absolute terms, and the popularity nudge pushed every well-known film above 10 for everyone. It now uses each member's own ranking, as GFAR does, so one generous scorer can't crowd out the others.
4. **Slow first request.** Loading the catalog took about 4 seconds on the first request. It now loads in the background at startup, and warm requests take 10–30 ms.

## Tests

| Suite | Result |
|---|---|
| python-ml-service (pytest + ruff check and format) | **168 passed** (61 new: features, user vector, model, group strategies, catalog/DB) |
| core-api (`./mvnw verify`) | **1180 passed**, 0 failed (after the pool change) |
| Persona tooling | **238 passed** |
| API type-drift check | Clean (98 types) |

## Limits and next steps

- **Deploy note:** core-api doesn't rebuild stored taste vectors when the model changes. Locally I marked all 317 for rebuild. Production needs the same one-off, or a small core-api change that re-marks vectors whose `model_version` differs.
- **Production setup:** `ml_reader` needs a login password on hosted Supabase, set as `ML_DATABASE_URL` on Railway. Without it, the model runs but keeps core-api's order.
- **No country or language signal**, so world-cinema fans (Nollywood, Bollywood, Korean) get genre matches, not their cinema. The next feature to add is TMDB `original_language` / production countries. It needs a catalog column, but no contract change.
- **Mood rules are crude** where only a genre hints the mood (*12 Angry Men* as "a good cry"). Adding keywords, or deriving moods from overview embeddings (Phase 1), would fix it.
- **Big groups (8 people) stay hard.** Fairness reaches 10%; a "satisfaction ledger" across sessions (Phase 2) would help.
- **Not built yet (Phase 1, needs your go-ahead):** impression and session logging, calibration-aware re-ranking, and adaptive calibration cards. Logging should come first, because it can't be added after the fact.

## Files

- **New:**
  - `python-ml-service/app/services/` `content_recommender.py`, `features.py`, `catalog.py`, `group_strategies.py`
  - `python-ml-service/tests/` `fixtures.py`, `test_features.py`, `test_user_vector.py`, `test_content_recommender.py`, `test_group_strategies.py`, `test_catalog.py`
- **Changed:**
  - `recommender.py` (`get_recommender()` returns content-v1)
  - `main.py` (catalog warm-up)
  - `config.py`
  - `tests/conftest.py`, `tests/test_api.py`, `tests/eval/test_run.py`
  - `ContentFilterService.POOL_SIZE`
  - READMEs and CLAUDE.md
- **Local only:** `ml_reader` was given a local login password. It isn't in the repo.


---

## Update 1: settings sweep on the persona groups

**How it was tested:** I captured core-api's exact group requests for the 40 persona groups once, then replayed them offline against different settings. The replay reproduced the live numbers exactly. I tried 96 combinations of four settings:
- the share of the hated-genre guard in the taste match
- the mood weight
- the quality/popularity weight
- the loved-film similarity weight

I then tried 15 fairness settings.

**The metric** comes from `tools/personas/sessions.py`:
- A member's **fit** for a movie is the average of their stated onboarding scores (1–10) for its genres, with unscored genres counting as 5.
- A member **enjoys** a movie if that fit is at least 7.
- **Worst member enjoys** is, per group, the lowest share of enjoyed picks among its members, averaged over the 40 groups.

**Result:** the best setting raised worst-member-enjoys from 39% to about 45%, and the least-happy member's average fit from about 6.0 to 6.4. **I didn't adopt it.**
- It works by switching off the quality nudge and the hated-genre guard, which the persona metric can't see.
- On MovieLens, which uses real ratings, those settings drop personal NDCG@10 from 0.078 to 0.038–0.065, and they hurt dissimilar groups.
- The fairness-only settings were already at their best.

**Lesson:** the persona metric is a sanity check built on stated preferences. Tuning on it alone overfits.

## Update 2: hybrid-v1, adding item-kNN collaborative filtering

This is the "next step" in `docs/recommender-directions.md`: learn from which films people rate alike.

**What it does:**
- `collaborative.py` builds item-kNN neighbours: adjusted cosine on mean-centred ratings, shrunk by co-rater count, keeping the top 50.
- It uses all of `app.ratings`, rebuilt hourly in the background. Each user's own ratings are read fresh per request.
- Taste becomes `(1−β)·content + β·kNN`, where `β = 0.9·support/(support+2)` and support is how many of the user's rated films are neighbours of the movie.
- With no co-rating evidence β = 0, which is exactly content-v1.
- New explanation: "Because you rated *Hereditary* 10/10". It only ever names a film the user liked.
- **No contract change.**

**Model choice:** EASE was also built and compared. It lost (NDCG@10 at most 0.082 vs 0.091), so item-kNN is served; its explanations are also more faithful. I tuned K, shrinkage, β and κ on MovieLens train data. Results plateau around 0.089–0.091.

### Offline results (MovieLens)

**Personal recommendations:**

| | NDCG@10 | Hit rate@10 | Precision@10 | Recall@10 |
|---|---|---|---|---|
| **hybrid-v1** | **0.0906** | **0.405** | **0.077** | **0.054** |
| content-v1 | 0.0775 | 0.390 | 0.068 | 0.049 |
| Popularity | 0.0778 | 0.345 | 0.061 | 0.050 |

**Groups:** mean member satisfaction, hybrid-v1 vs content-v1.

| Group type | mean | least misery | closeness | fairness |
|---|---|---|---|---|
| Similar | 0.064 vs 0.059 | **0.068** vs 0.056 | 0.066 vs 0.061 | 0.067 vs 0.061 |
| Random | 0.057 vs 0.053 | **0.060** vs 0.050 | 0.060 vs 0.052 | 0.057 vs 0.050 |
| Dissimilar | 0.037 vs 0.032 | **0.041** vs 0.036 | 0.038 vs 0.032 | 0.038 vs 0.035 |

- hybrid-v1 now beats popularity for every group type with least misery and closeness.
- No contract violations.
- About 51 ms per personal call and 151 ms per group call (9.7k-movie pools).

Report: `python-ml-service/eval/reports/20261118-hybrid-v1.md`.

### Live persona sessions

| Strategy | content-v1 | hybrid-v1 |
|---|---|---|
| Mean | 39.2% | 39.4% |
| Least misery | 35.9% | 35.8% |
| Fairness | 42.1% | 42.0% |

There were 0 comfort violations across 120 sessions (`tools/personas/out/session-report-hybrid-v1.md`).

**Why persona results barely move:** the CF share averages under 1% there. The 300 personas have about 50 synthetic ratings each, spread over 7,600 movies, so neighbouring films are almost never co-rated, and only about a third of each candidate pool is in the CF model. That's the designed fallback, and it's what an early launch looks like. β grows on its own as real users rate overlapping films.

### Tests

| Suite | Result |
|---|---|
| ML service | **185 passed** (17 new: collaborative model, blend, explanation, Postgres source) |
| ruff | clean |
| core-api | unchanged since the 1180-test run |

### Next options (your call)
- **Logging** impressions and session outcomes. It's still the top Phase 1 item, and it's what lets CF and the group strategies learn from real picks.
- **MF/iALS** in the latent vector field, once there are a few thousand users.
- A **friend-graph prior** for brand-new users (an average of friends' tastes).

---

## Update 3: the experiment loop, rounds 1–6 → hybrid-v6 (2026-11-19)

**How:** subagents ran experiments in parallel. Each variant was scored on two test beds and
recorded on a leaderboard (`python-ml-service/eval/experiment.py`, rows in
`eval/experiments/leaderboard-v*.jsonl`).

- **MovieLens:** real ratings.
  - Production-like 1000-movie pool.
  - Real TMDB metadata from the local catalog.
  - Groups of similar, random and dissimilar users.
  - A new-user test where users keep only 5 ratings.
- **Persona friend networks** (`eval/personas.py`): 20% of each persona's ratings are held out.
  - Uses the 40 seeded friend groups with real closeness.
  - Results are split by group type (easy, pair, hard, big) and by measured taste similarity.
  - The personas are synthetic, so treat these numbers as relative only.

Winners were ported into the model with tests and then confirmed on a second sample (seed 7, 25% held out) before shipping.

**What shipped:**

| Version | Change | Gain (full run vs the version before) |
|---|---|---|
| hybrid-v2 | Explicit + implicit ("who watched what") item-kNN blend. Groups get a merged-profile pseudo-member that pools members' ratings. | +16% |
| hybrid-v3 | Friend signal: friends' PUBLIC/FRIENDS ratings, never PRIVATE ones; blocks and suspended accounts respected. Groups get the members' friends (permissive rule, owner-approved). | persona accuracy +65%, persona groups ×5 |
| hybrid-v4 | "Fill": once a user has ratings, genres they never touched count as their average, not 5/10. | new users +26% |
| hybrid-v5 | No merged-profile friend signal for groups of 6+. Soft least misery for 3+ members (protects the least happy half). | persona groups +7%, big groups +29% |
| hybrid-v6 | iALS matrix factorisation, NumPy only, as a bonus on the blend (owner-approved after a 2nd-sample check). | +5.5%, and +9.5% on the 2nd sample |

**hybrid-v6 now** (MovieLens, all 590 eligible users, 1000-movie pool):
- Hit rate@10 is 49% (54% on the 2nd sample). The version first pushed (hybrid-v1) had 36%.
- NDCG@10 is 0.123, against 0.077 for hybrid-v1.
- New users (5 ratings) have NDCG 0.038.
- **Groups:** about +20–30% over hybrid-v1. Similar-taste groups gain the most.
- **Live:** group requests take about 0.1 s, and 120 persona sessions ran with 0 comfort violations.

**Tried and dropped:**
- **Group score scaling** (z-score / rank): hurt least misery.
- **Average-without-misery, Borda, adaptive strategy:** within noise.
- **Pool-relative prior:** neutral. It exposed that persona ratings ignore popularity.
- **No-shrinkage kNN:** the gain was mostly noise and support inflation.
- **Keyword TF-IDF look-alike and profile:** neutral for warm users.
- **Calibrated lists (Steck):** a product choice, not an accuracy gain. At λ 0.5, miscalibration is −18% for −0.5% NDCG, but new users lose 6%.
- **Recency:** can't be measured on this data. MovieLens users rate their history within a day, and persona timestamps are seed times.

**Lessons about the evaluation itself:**
1. **The stated-genre persona metric can be gamed.** Ignoring quality and the hated-genre guard scored well on it.
2. **Whole-catalog candidate pools let the obscurity penalty swamp taste.** The fix was a production-like pool plus real TMDB metadata.
3. **Tiny persona group metrics can explode composite scores.** Ratios are now clipped.
4. **Single-sample gains need a second sample** before shipping.

**Open decisions and next steps:**
- **Calibration** for "For you", if lists should reflect all of a user's tastes.
- **Persona realism:** regenerate persona ratings with a realistic popularity skew.
- **Logging:** record impressions and session outcomes. This is still the top Phase 1 item, and it's the only way to measure real accuracy and recency.
- **Deploy:**
  - give `ml_reader` a login on hosted Supabase;
  - rebuild stored vectors after deploying;
  - the friend signal reads `ratings.visibility`, `blocks` and `profiles.status` through `ml_reader`.

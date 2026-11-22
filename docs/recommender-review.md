# FilmFuse recommender: what was built, how it works, and the experiments behind it

Review document for the owner, written 2026-11-22. The model is **hybrid-v7**, pushed as `7be25dd` on `session-swipe-deck`.

Deeper detail:
- `docs/content-v1-report.md`: the version-by-version history.
- `docs/overnight-experiments-2026-11-19.md`: the ml-32m tuning tables.
- `docs/recommender-directions.md`: the original research and options.
- `python-ml-service/README.md`: the code map.

---

## 1. The short version

FilmFuse uses a **hybrid recommender**. Each movie gets one predicted score (on the 1–10 scale) built from several signals, and groups combine their members' scores with a chosen strategy.

| Signal | Style of modelling | What it captures |
|---|---|---|
| Content | Content-based filtering on 27 interpretable dimensions (19 genres + 8 moods) | "You like horror and dark moods" |
| Neighbours | Item-item collaborative filtering (item-kNN), explicit + implicit | "People who rated Alien 9/10 also loved this" |
| Matrix factorisation | iALS: implicit alternating least squares, 64 hidden taste factors | Patterns the neighbour method can't see |
| Friends | Trust-based social CF from the friend graph | "Your friends rated it highly" |
| Prior | Quality × popularity with an obscurity penalty | A nudge towards good, known films, strongest for new users |

**For groups:**
- Every member is scored.
- A merged-profile "pseudo-member" pools the group's ratings.
- The scores are combined with mean, least misery, most pleasure, closeness-weighted or a GFAR-style fairness strategy.

**Accuracy now** (MovieLens ml-32m, full runs on 2 samples of 3,000 users, 10k candidate pool):

| Measure | Value |
|---|---|
| Hit rate @10 | 50–53% |
| NDCG @10 | 0.129–0.134 |

**The journey:**

| Version | Hit rate @10 | NDCG @10 | Dataset |
|---|---|---|---|
| hybrid-v1 | 36% | 0.077 | ml-latest-small |
| hybrid-v6 | 49% | 0.123 | ml-latest-small |
| hybrid-v7 | 50–53% | 0.129–0.134 | ml-32m |

---

## 2. How a score is built

For one user and one candidate movie:

```
content   = taste affinity (0.7 × weighted mean of matching dims + 0.3 × lowest genre dim)
            + similarity to loved / disliked movies
cf        = explicit item-kNN prediction ("sum" mode)
            + 2.0 × log1p(implicit kNN evidence)          # who-watched-what
            + 3.0 × z-scored iALS preference               # hybrid-v7: was 1.0
beta      = 1.0 × support / (support + 1.0)                 # how much CF evidence exists
taste     = (1 − beta) × content + beta × cf
predicted = taste + friend signal + quality/popularity prior
```

**Rules on top:**
- **No CF evidence:** with no ratings, or for a movie nobody co-rated, beta is 0 and the model is pure content scoring. So it never breaks for brand-new users or movies.
- **Hard dislikes:** explicit dislikes and least favourites are rejected outright.
- **Fill:** once a user has ratings, any genre they never scored counts as their own average, not a neutral 5.
- **Time:** older ratings count for less.
- **Explanations** come from the largest real contribution to the score, for example "Because you rated Alien 9/10", "Like Coco, which you loved", "Your friends rated it highly" or "Because you love Horror". Group explanations never single out a member.

**Serving:**
- **Pool:** core-api sends up to **10,000** eligible candidates (most popular first, after comfort filters). The ML service ranks only those, and core-api re-checks every result.
- **Freshness:** the kNN tables and iALS item factors rebuild in the background. A user's own latest ratings are read on every request, and iALS "folds them in" with one small solve.
- **Speed:** about 80 ms per user and 390 ms per group at 10k candidates, against a 5 s budget.
- **Dependencies:** NumPy only, no ML frameworks.

**Privacy:**
- **Visible ratings only:** the friend signal uses only PUBLIC and FRIENDS ratings, never PRIVATE ones. It skips blocked users and suspended accounts, following core-api's `RatingVisibilitySql` rules.
- **Large groups:** groups of 6 or more don't get the friend signal on the merged profile.

---

## 3. Groups

| Strategy | What it optimises |
|---|---|
| Mean | Average happiness |
| Least misery | The least happy member. "Soft" for 3+ people: protects the unhappiest half, not one person |
| Most pleasure | The happiest member |
| Closeness-weighted | Blends mean and least misery by how close the group is |
| Fairness (GFAR-style) | Rank-based: each member gets some of their top picks into the list |

The **merged profile** helps most for similar-taste groups. It treats the group as one extra "member" whose ratings are everyone's ratings pooled, weighted 0.7 against the members' own scores. Removing it costs 3–4% overall and 4–9% on the group metric.

---

## 4. How the experiments were run

**Two test beds**, both in `python-ml-service/eval/`:

1. **MovieLens** (real people's ratings; evaluation only, never used in the product):
   - **Hold-out:** each user's latest 20% of ratings are hidden, and the model must rank the movies they loved (rated ≥ 8) into the top 10.
   - **Candidates** are a production-like pool (1,000 or now 10,000 most popular unseen films) with real TMDB metadata from the local catalogue.
   - **New-user test:** users keep only 5 ratings.
   - **Groups** are built from similar, random and dissimilar users. A group "hit" needs every member's hidden favourites to rank well.
   - **Datasets:** first ml-latest-small (100k ratings, 610 users). Since 2026-11-19, three samples of 3,000 users from ml-32m (32M ratings).
2. **Persona friend networks** (`eval/personas.py`):
   - **Hold-out** on the 300 seeded personas and their 40 friend groups, with real closeness scores.
   - **Results** are split by group type (easy, pair, hard, big) and by taste similarity.
   - **Synthetic ratings**, so use these numbers only to compare versions.

**One composite score** per run (`eval/experiment.py`), relative to a baseline run on the same data:

| Metric | Weight |
|---|---|
| ml_ndcg (individuals) | 0.30 |
| ml_group | 0.25 |
| ml_cold_ndcg (new users) | 0.10 |
| ml_similar (similar-taste groups) | 0.10 |
| p_ndcg (personas) | 0.15 |
| p_group (persona groups) | 0.05 |
| ml_ndcg_full (whole catalogue) | 0.05 |

Each ratio is clipped to 0.67–1.5 so one tiny metric can't dominate. 1.000 = baseline; above 1 is better.

**Rules I followed:**
- **Change one thing at a time** first, then try combinations.
- **Quick mode** (200 users) only to screen. Every shipped change was confirmed in **full mode on a second sample**.
- **MovieLens decides.** The persona "stated genre" measure can be gamed (switching off quality signals scores well on it), so it is never trusted alone.
- **Ask the owner first** before any new model family or new signal: friends, iALS, and now the pool size.
- **Isolated subagents:** subagents ran rounds in parallel, each writing only its own experiment file and leaderboard rows.

---

## 5. Experiment log (what was tried, what happened)

### Shipped

| Version | Change | Evidence |
|---|---|---|
| content-v1 | Genre + mood matching, quality prior | Tied "most popular" (NDCG 0.0775 vs 0.0778): the content ceiling |
| hybrid-v1 | + item-kNN collaborative filtering | Hit rate 36%, NDCG 0.077 → clearly above popularity on later pools |
| hybrid-v2 | Explicit + implicit kNN blend; merged group profile | +16% composite |
| hybrid-v3 | Friend signal (owner-approved, permissive group rule) | Persona accuracy +65%, persona groups ×5 |
| hybrid-v4 | "Fill" unset genres with the user's own average | New users +26% |
| hybrid-v5 | No friend signal on merged profile for 6+; soft least misery | Persona groups +7%, big groups +29% |
| hybrid-v6 | iALS matrix factorisation as a bonus (weight 1.0) | +5.5%, +9.5% on 2nd sample; ml-32m: removing it scores 0.909–0.912 on all 3 samples |
| **hybrid-v7** | **iALS weight 3.0; candidate pool 10,000** | Weight 3: +4.4% / +4.8% (full, 2 samples). With the 10k pool: 1.046 / 1.038 |

### iALS tuning (ml-32m, 2026-11-21)

| Setting | Result |
|---|---|
| Weight 0.5 / 1.5 / 2.0 / 2.5 / 3.0 / 4.0 / 5.0 (quick) | 0.966 / 1.022 / 1.030 / 1.038 / **1.055** / 1.055 / 1.052 |
| Weight 2.0 / 3.0 (full, s2 & s3) | 1.026, 1.025 / **1.044, 1.048** |
| Factors 32 / 96 | 0.987 / 0.999 |
| Regularisation 30 / 300 | 1.011 / 0.979 |
| Alpha 10 / 100 | 0.995 / 1.003 |

**Earlier iALS design choices (round 6, ml-latest-small):**
- **Preference:** graded, from ratings (beat binary "rated or not").
- **Confidence:** linear in the rating.
- **Scaling:** the iALS score is z-scored among candidates.
- **Role:** used as a bonus on top of the kNN blend. Replacing the implicit kNN with iALS scored 0.974, and letting iALS switch CF on where kNN had no evidence was not better.

### Candidate pool size (ml-32m, 2026-11-21)

| Pool | Weight 1.0 | Weight 3.0 (s2 / s3) | Variety | Time per user / group |
|---|---|---|---|---|
| 1k | 1.000 | 1.044 / 1.048 | baseline | 11 / 56 ms |
| 3k | 1.013 | 1.046 / 1.049 | +18% | 33 / 166 ms |
| 5k | 1.013 | — | — | 57 / 275 ms |
| 10k | 1.023, 1.018 | 1.046 / 1.038 | +32% | 108 / 565 ms (offline, shared CPU); 80 / 390 ms live |

The owner chose 10k for variety. Calibration keeps a 1k fallback pool.

### Blend tuning (ml-32m, all with weight 3; reference 1.055 quick)

| Setting | Result |
|---|---|
| KAPPA 0.5 | 1.068 quick, but only 1.050 vs 1.044 in full (MovieLens metrics within 0.001). Not shipped |
| Implicit weight 1.0 | 1.059 (noise) |
| Neighbourhood 200 / 50 | 1.055 / 1.041 |
| BETA_MAX 0.8 | 1.040 |
| KAPPA 2 | 1.003 |

### Re-checks of earlier decisions on ml-32m (quick, s1 / s2)

| Decision undone | Score | Verdict |
|---|---|---|
| CF weight back to BETA_MAX 0.9 / KAPPA 2 | 0.980 / 0.950 | Current setting holds |
| No merged group profile | 0.969 / 0.965 | Holds |
| No new-user fill | 0.991 / 1.000 | Weakly holds |
| Hard least misery | 1.000 / 0.999 | Neutral |

### Tried and dropped

- **Group score scaling** (z-score / rank before combining): hurt least misery.
- **Borda count, average-without-misery, adaptive strategy:** within noise.
- **Pool-relative prior:** neutral. It also exposed that persona ratings ignore popularity.
- **kNN without shrinkage:** the gain was noise and support inflation.
- **EASE** (a linear item model): NDCG 0.08 vs kNN 0.09.
- **Keyword TF-IDF look-alike / profile:** neutral for users with ratings.
- **Calibrated lists (Steck):** −18% miscalibration for −0.5% NDCG, but new users lose 6%. A product choice, kept optional.
- **Recency weighting:** can't be measured here. MovieLens users rate their history in a day, and persona timestamps are seed times.
- **Splitting the iALS weight** between individuals and groups: one sample's gain didn't repeat.
- **TMDB "similar films" signal:** built behind a switch (`eval/experiments/tmdb_similar.py`). Not run yet: it needs your TMDB token and a licence decision.

---

## 6. What I learned about evaluating this

1. **The persona stated-genre metric can be gamed.** Turning off quality and the hated-genre guard scored well on it. MovieLens is the referee.
2. **Ranking the whole catalogue misleads.** The obscurity penalty swamped taste, so the fix was a production-like pool with real metadata.
3. **Tiny metrics explode ratio scores.** That's why ratios are clipped.
4. **Small-data gains can be noise.** Single-sample wins need a second sample. ml-32m changed the best iALS weight from 1 to 3.
5. **Quick runs (200 users) carry ±2–3% noise.** KAPPA 0.5 looked like +1.3% in quick mode and +0.6% in full.
6. **Overlapping gains don't add.** The bigger pool and the higher iALS weight both surface less-mainstream films, so together they gain less than the sum.

---

## 7. Where the raw details are

| What | Where |
|---|---|
| Every run, with all metrics, overrides and timings | `python-ml-service/eval/experiments/leaderboard*.jsonl` (239 rows: rounds 1–2 in `leaderboard.jsonl`, v2–v3 for the production-pool rounds, v4 since hybrid-v6 incl. ml-32m) |
| Leaderboard table | `cd python-ml-service && .venv/bin/python -m eval.experiment show --top 50` |
| The code of each experiment | `python-ml-service/eval/experiments/*.py` (one file per idea: `mf_ials.py`, `cf4_variants.py`, `ct_social.py`, `grp_variants.py`, `cr_calib.py`, `ials_tune.py`, `blend_tune.py`, …) |
| Harness | `eval/harness.py` (MovieLens), `eval/personas.py` (friend networks), `eval/experiment.py` (runner + composite score) |
| Re-running a check | `ML_DATABASE_URL=... .venv/bin/python -m eval.experiment run --name X --data eval/data/ml-32m-u3000-s2 [--pool-size N] [--overrides '{"cf.MF_WEIGHT": 3.0}']` |

---

## 8. Open items

- **Deploy:**
  - Rebuild stored user vectors after deploying (model version bump).
  - Give `ml_reader` a login on hosted Supabase.
  - Time the 10k pool query on hosted Postgres (about 90 ms locally).
- **Persona session check for v7:** blocked by local Supabase sign-in timeouts (HTTP 504). It still fails after `supabase stop/start`, so it needs a look at the local auth container.
- **Biggest next win: logging** impressions and session picks. It's the only way to measure real accuracy and recency, and to learn from what groups actually choose.
- **Your decisions:**
  - Run the TMDB similar-films prototype? Needs your token, plus a licence check.
  - Calibrated "For you" lists, as a product choice.
  - Regenerate persona ratings with a realistic popularity skew.
- **Tech debt:** `MF_FACTORS`, `MF_REG`, `MF_ALPHA`, `BLEND_K` and `IMPLICIT_WEIGHT` are fixed at import, so experiment overrides can't reach them (factory files work around it). Reading them at call time would fix it.

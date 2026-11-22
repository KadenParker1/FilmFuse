# Overnight recommender experiments (2026-11-19 → 2026-11-21)

Report only. No model defaults, app code or core-api settings were changed, and nothing was committed.

## Bottom line

| # | Recommendation | Evidence | Change |
|---|---|---|---|
| 1 | **iALS weight 1.0 → 3.0** | +4.4–4.8% composite in full runs on 2 samples; better for individuals, groups and new users | `MF_WEIGHT = 3.0` in `python-ml-service/app/services/collaborative.py` |
| 2 | **Candidate pool 1,000 → 3,000** | Accuracy equal or slightly better than 1k with weight 3, and +18% more distinct movies recommended; ~3× scoring time, still well under 0.5 s | `POOL_SIZE = 3000` in core-api `ContentFilterService.java` |
| — | Everything else stays as it is | Past decisions still hold on the bigger data; other settings are neutral or worse | — |

Shipping 1 means bumping `MODEL_VERSION` to hybrid-v7 and updating `CLAUDE.md` and `docs/content-v1-report.md`. Shipping 2 needs a check that the popularity-ordered pool query stays fast at 3k on hosted Postgres; the request to ML grows to ~3k ids (~20 KB).

## Setup

- **Data:** three samples of 3,000 users from MovieLens ml-32m (`eval/data/ml-32m-u3000-s1|s2|s3`), real TMDB metadata, 1,000-movie production-like candidate pool unless stated. Evaluation only; MovieLens is not used in the product.
- **Score:** composite score from `eval/experiment.py` relative to the hybrid-v6 baseline on the same sample (1.000 = baseline; > 1 better).
- **Run types:** "full" runs use all users and 100 groups per method. "Quick" runs use 200 users and 40 groups per method, which is noisy (±2–3%), so quick runs were only used for screening and every recommendation above is confirmed in full.

## 1. Is iALS worth keeping? Yes

hybrid-v6 without iALS (`MF_WEIGHT 0`) against hybrid-v6, full runs:

| Sample | Score without iALS | NDCG (with / without) | Hit rate | New users | Groups | Similar-taste groups |
|---|---|---|---|---|---|---|
| s1 | 0.909 | .122 / .107 | .493 / .450 | .0303 / .0296 | .0353 / .0320 | .0864 / .0699 |
| s2 | 0.912 | .128 / .111 | .512 / .465 | .0321 / .0304 | .0386 / .0347 | .0974 / .0908 |
| s3 | 0.909 | .123 / .108 | .479 / .443 | .0314 / .0305 | .0388 / .0342 | .1056 / .0933 |

iALS wins every metric on every sample.

## 2. Do past decisions still hold? Yes (quick runs, s1 / s2)

| Decision undone | Score | Verdict |
|---|---|---|
| Current CF weight (back to BETA_MAX 0.9, KAPPA 2) | 0.980 / 0.950 | Holds |
| Merged group profile | 0.969 / 0.965 | Holds (group metric drops 4–9%) |
| New-user fill | 0.991 / 1.000 | Weakly holds (helps new users on s1) |
| Soft least misery | 1.000 / 0.999 | Neutral; keep |

## 3. iALS tuning

Screen (quick, s1, one setting at a time):

| Setting | Score |
|---|---|
| Weight 0.5 / 1.5 / 2.0 / 2.5 / **3.0** / 4.0 / 5.0 | 0.966 / 1.022 / 1.030 / 1.038 / **1.055** / 1.055 / 1.052 |
| Regularisation 30 / 300 | 1.011 / 0.979 |
| Alpha 10 / 100 | 0.995 / 1.003 |
| Factors 32 / 96 | 0.987 / 0.999 |
| Reg 30 + weight 1.5 | 1.025 (reg adds nothing) |

Full confirmations:

| Weight | s2 | s3 |
|---|---|---|
| 1.5 | 1.019 | 1.020 |
| 2.0 | 1.026 | 1.025 |
| **3.0** | **1.044** | **1.048** |

At weight 3.0 vs baseline (s2 / s3):
- NDCG .134 vs .128 and .129 vs .123.
- Groups +5–7%, similar-taste groups +4–6%, new users +2%.
- Personas: group score +22%, individual score flat.

Above 3, individual accuracy starts to slip, so 3.0 is the pick.

## 4. Candidate pool size (requested 2026-11-21)

New eval flag: `python -m eval.experiment run --pool-size N`. It is scored against the 1k baseline, and the pool is ordered by popularity like core-api's.

**With the current iALS weight (1.0):**

| Pool | Score s1 | Score s2 | Time per user / group |
|---|---|---|---|
| 1k | 1.000 | 1.000 | 11 / 56 ms |
| 3k | 1.013 | | 33 / 166 ms |
| 5k | 1.013 | | 57 / 275 ms |
| 10k | 1.023 | 1.018 | 108 / 565 ms |

**With iALS weight 3.0:**

| Pool | s2 | s3 | Variety (distinct movies recommended) |
|---|---|---|---|
| 1k | 1.044 | 1.048 | baseline |
| **3k** | **1.046** | **1.049** | **+18%** |
| 10k | 1.046 | 1.038 | +32% |

The two gains overlap: both surface less-mainstream films the user would like. With weight 3, a 10k pool starts to cost accuracy (groups on s3 .0399 vs .0414). 3k keeps all the accuracy and adds variety. Times were measured while other runs shared the CPU, so they are upper bounds.

## 5. Blend tuning (all with iALS weight 3; quick s1 reference 1.055)

| Setting | Score |
|---|---|
| KAPPA 0.5 | 1.068 quick; **full s2 1.050 vs 1.044** (MovieLens metrics within 0.001; the new-user gain didn't repeat) |
| Implicit weight 1 | 1.059 (noise) |
| Neighbourhood 200 | 1.055 |
| Neighbourhood 50 | 1.041 |
| BETA_MAX 0.8 | 1.040 |
| KAPPA 2 | 1.003 |

The current blend settings hold. KAPPA 0.5 is optional, and the evidence is too thin to recommend it.

## What went wrong / notes

- **The Mac slept** for most of the first night and part of the next afternoon, even with `caffeinate -i` (lid closed / battery). Runs that take ~1 h awake took 6+ h. Use a plugged-in Mac with the lid open, or a cloud box, for future overnight queues.
- **Some iALS and blend settings can't be overridden.** `MF_FACTORS`, `MF_REG`, `MF_ALPHA`, `BLEND_K` and `IMPLICIT_WEIGHT` are default arguments bound at import, so `cf.*` overrides silently don't reach them. Experiments used factory files instead (`eval/experiments/ials_tune.py`, `eval/experiments/blend_tune.py`). Worth fixing by reading the constants at call time.
- **Concurrent runs** wrote to one shared log and interleaved lines. The leaderboard (`leaderboard-v4.jsonl`) is the source of truth and was unaffected.
- **Brief database timeouts** while the Mac was waking up; the runs retried and finished.

## Files touched (uncommitted)

- `python-ml-service/eval/experiment.py`: `--pool-size` option, recorded as `pool_size` on leaderboard rows.
- `python-ml-service/eval/experiments/ials_tune.py`, `python-ml-service/eval/experiments/blend_tune.py`: experiment factories.
- `python-ml-service/eval/experiments/leaderboard-v4.jsonl`: new rows.
- This document.

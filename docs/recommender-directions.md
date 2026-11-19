# Start interpretable, then earn the latent layer

FilmFuse's best single-user direction at launch is **content-based scoring of the user's interpretable genre+mood vector against TMDB item features**, backed by a TMDB popularity prior and an adaptive onboarding/swipe loop. Collaborative filtering (item-kNN first, then matrix factorisation folded into the optional latent part) comes in only once real co-ratings exist. For groups, the five contract strategies are cheap deterministic aggregators that the literature supports, with two caveats. First, `closeness_weighted` should not mean "weight members by centrality", because real-group studies find that centrality hardly predicts influence. Second, `fairness` is best defined over the whole list, and over past sessions for persistent groups, rather than per item. Learned group models (AGREE, GroupIM, ConsRec) and LLM aggregators are poor fits now: the learned models need group logs FilmFuse doesn't have, and LLMs quietly fall back to averaging and misdescribe what they did. The deeper finding is that **method choice matters less than data, features, tuning and evaluation hygiene**. Tuned simple baselines match or beat most published neural recommenders, and implementation variance often exceeds the reported gaps between methods. The highest-leverage work now is therefore not a clever model. It is fixing a leakage issue in the offline harness, evaluating cold start separately, logging every served slate and group session, and keeping explanations faithful to the signals that actually produced each score. Everything below is options and trade-offs for the owner to choose from, not a prescribed algorithm.

## Tuned simple models beat architecture choice on MovieLens

The reproducibility literature should set FilmFuse's expectations. Dacrema, Cremonesi and Jannach tried to reproduce 18 neural top-n recommenders. **Only 7 could be reproduced, and 6 of those were often beaten by nearest-neighbour or graph heuristics** ([DeepAI](https://deepai.org/publication/are-we-really-making-much-progress-a-worrying-analysis-of-recent-neural-recommendation-approaches)). The extended version found **11 of 12 reproducible neural CF methods beaten by conceptually simple ones** ([DeepAI](https://deepai.org/publication/a-troubling-analysis-of-reproducibility-and-progress-in-recommender-systems-research)). Rendle et al. showed that a properly tuned plain dot product "substantially outperforms" NCF's learned MLP similarity, and that the dot product also enables efficient retrieval ([arXiv 2005.09683](https://arxiv.org/abs/2005.09683)). A follow-up found that tuned iALS is competitive with much newer models on ML-20M ([ar5iv 2110.14037](https://ar5iv.arxiv.org/html/2110.14037)).

The gaps that do exist are small. On ML-20M, EASE scores roughly **0.39 Recall@20 against roughly 0.42 for the best-listed model**. These are aggregator figures and partly unverified ([sotaverified](https://sotaverified.org/papers/190503375)). Implementation and protocol differences are larger. BERT4Rec's NDCG@10 on ML-1M ranged from **0.055 to 0.156 depending only on the implementation** ([arXiv 2207.07483](https://arxiv.org/pdf/2207.07483)). A better loss and better negatives lifted SASRec from 0.134 to 0.182, a 36% gain that exceeds the SASRec-vs-BERT4Rec architecture gap ([arXiv 2309.07602](https://arxiv.org/pdf/2309.07602)).

Two consequences follow for FilmFuse. First, there is no reason to reach for neural architectures early. Second, every comparison in the MovieLens harness must be against tuned baselines, popularity included, or the team will repeat the self-deception these papers document. One gap remains open: **no study was found of content-only vs CF vs hybrid performance at FilmFuse's real scale** of hundreds to low thousands of users. Any claim about which family wins at that scale is a judgement, not evidence.

## Single-user directions: content first, CF as a later latent layer

The single-user options fall into six families. They differ most on three axes FilmFuse cares about: whether they work with zero co-ratings, whether they produce faithful explanations, and whether they fit a CPU-only, read-only service. The table summarises the trade-offs. The prose after it covers the points that need nuance.

| Direction | Works at day one? | Explanation quality | CPU / ops fit | Main risk for FilmFuse |
|---|---|---|---|---|
| TMDB popularity / vote-weighted prior | Yes | Weak ("popular") | Trivial | Bland; it is the baseline every model must beat |
| Linear content scoring: interpretable genre+mood user vector · item genre/mood features | Yes, from onboarding | Excellent, per dimension | Trivial (NumPy or pgvector) | Accuracy ceiling; mood tags on items must be derived |
| TF-IDF over TMDB keywords | Yes | Good ("has keyword X") | Cheap, sparse | Noisy keywords; sparse long tail |
| Sentence embeddings of overviews + pgvector kNN | Yes | Moderate ("similar to a film you rated 9") | One-off batch embed; fast ANN | Little benchmark evidence; similarity ≠ preference |
| Item-kNN CF (sparse top-k neighbours) | No, needs co-ratings | Excellent ("because you rated X 9/10") | Cheap if limited to rated items | Coverage limited to items with interactions |
| MF / iALS (dot product), folded into latent part | No | Weak unless backed by neighbours | Cheap to serve; nightly batch train | Needs a dense enough matrix; versioning of the latent space |
| EASE | No | Moderate (item-item weights) | **Impractical on the full catalog** | Dense item×item inverse |
| LightFM-style metadata+latent hybrid | Partly | Moderate | Fine | Upstream inactive; forks only |
| Contextual bandit over swipes (LinUCB / Thompson on interpretable dims) | Yes | Good ("checking if you like X") | Tiny per-user state | Exploration can annoy users if overused |
| Sequential (SASRec, BERT4Rec), GNN (LightGCN) | No | Poor | Adds PyTorch and training ops | Needs long histories and many users |
| LLM reranker or explanation writer | Yes (over candidates) | Fluent but unfaithful unless grounded | Hosted API latency and cost | Position bias; trails trained models |

### Content scoring is the only approach that works from the first session

Content-based filtering recommends brand-new movies immediately, which is exactly the item cold-start case ([TMDB project write-up](https://dquerales.github.io/DanielPortfolio/recommend_system.html)). The frozen contract already makes the user vector a set of interpretable genre+mood dimensions. So the simplest day-one model is a hand-built two-tower: a stated-preference user vector, updated by ratings, favourites, swipes and dismissals, dotted with item feature vectors. It is cheap, it maps one-to-one onto the contract, and its explanations are faithful by construction.

Its weaknesses are an accuracy ceiling and dependence on item-side mood features. TMDB supplies genres and keywords but not "mood", so the owner must choose how to derive mood dimensions for 74k–150k films. That derivation is the single biggest quality lever in this direction. Attribute elicitation is a real signal, not a UX nicety: recent onboarding work finds that **asking about item attributes, not just items, makes CF more effective** ([arXiv 2510.27342](https://arxiv.org/html/2510.27342v1)). Google/YouTube prefer attribute onboarding because item-based elicitation cannot cover a large catalog in a short onboarding ([ICML 2023 slides](https://ICML.cc/media/icml-2023/Slides/29091.pdf)).

Overview embeddings are a natural candidate generator or latent sub-space, because they capture semantics that genre labels miss. Their explanations are weaker, though, and the evidence base is thin. Plot-similarity models lack clear ground truth, and one thesis fell back on checking cluster–genre alignment ([PoliTo thesis](https://webthesis.biblio.polito.it/22582/1/tesi.pdf)). One small study found that adding DeBERTa text embeddings to NCF improved MovieLens-100K accuracy ([Sogang thesis](https://dcollection.sogang.ac.kr/dcollection/srch/srchDetail/000000079795)). By my arithmetic (not benchmarked), 150k × 384 float32 vectors is about 230 MB, which pgvector handles comfortably.

### Onboarding and the swipe deck deserve more design effort than the ranker

The strongest single number in the single-user notes concerns elicitation, not modelling. Christakopoulou, Radlinski and Hofmann framed new-user onboarding as a bandit over which questions to ask, and reported a **25% improvement over a static model after only two questions** ([Microsoft Research](https://www.microsoft.com/en-us/research/publication/towards-conversational-recommender-systems/)). The 2025 elicitation work also finds that **pairwise "this or that" comparisons are more informative than single-item ratings** ([arXiv 2510.27342](https://arxiv.org/pdf/2510.27342)). That supports making FilmFuse's calibration swipes comparative where possible.

The option to weigh is whether the swipe deck stays a passive "show top predictions" feed or becomes an active-learning loop. In the active version, the next card is chosen to reduce uncertainty in the user vector, for example with LinUCB or Thompson sampling over the roughly 20–50 interpretable dimensions. The active version costs a small d×d matrix per user and gives honest explanations ("we're checking whether you like slow-burn horror"). Its risk is showing users films chosen to teach the model rather than to please them. Two cheaper signal rules apply in either version: treat dismissals as explicit negatives and favourites as strong positives. A friend-graph prior for brand-new users (a weighted average of friends' vectors) is plausible and cheap, but **no source quantified its benefit**.

### Collaborative filtering is a later layer, and item-kNN is the explainable entry point

MF, iALS, EASE and item-kNN all learn nothing until users co-rate items, so they are second-phase options. Among them, **item-kNN gives the most natural faithful explanation** ("because you rated *Alien* 9/10"). Its sparse neighbour lists, restricted to items that actually have ratings, are cheap.

MF/iALS factors fit naturally into the contract's optional latent part. Rendle's argument for dot-product scoring also suits pgvector inner-product search ([arXiv 2005.09683](https://arxiv.org/abs/2005.09683)). The price is versioning: latent vectors are tied to one trained model, so user vectors must be folded in against fixed item factors and re-derived on each retrain. iALS cost is also cubic in embedding dimension, which argues for small d ([iALS++ summary](https://arxiv.deeppaper.ai/papers/2110.14044v1)). For explicit 1–10 ratings, the top-N literature mostly binarises interactions. A confidence-weighted variant is the usual adaptation, but this is the owner's design choice.

EASE is competitive in benchmarks ([arXiv 1905.03375](https://arxiv.org/pdf/1905.03375)), but on the full catalog it needs a dense item×item inverse. By my arithmetic that is about **22–90 GB at float32 for 74k–150k items**, so it is only viable restricted to the few thousand rated items. LightFM's metadata+latent model is conceptually the exact hybrid FilmFuse describes. Upstream is inactive, though, with no PyPI release in more than 12 months ([Snyk](https://snyk.io/advisor/python/lightfm)), so it would have to be pinned to a fork such as `lightfm-next` ([PyPI](https://pypi.org/project/lightfm-next/)) or reimplemented.

### Neural sequential, GNN and LLM rankers are poor value at this scale

Sequential models need long, dense per-user histories, and a movie-rating app produces short, slow ones. LightGCN's reproduction finds it **does best for high-interaction users** ([arXiv 2312.16183](https://arxiv.org/pdf/2312.16183)), which is the wrong regime for FilmFuse. The more interesting graph idea is untrained one-hop smoothing over the *friend* graph ("3 friends rated this highly"). It is cheap and explainable, but no benchmark for small friend graphs was found.

Zero-shot LLM rankers are competitive with trained BPRMF/popularity when reranking candidates. They suffer from **position bias** that needs repeated shuffled queries to mitigate ([paper summary](https://papers.lunadong.com/paper/4647)), and they generally underperform trained sequential models ([arXiv 2401.04997](https://arxiv.org/pdf/2401.04997)). A 2026 study again finds position bias undermining preference consistency in LLM rerankers ([arXiv 2608.03091](https://arxiv.org/pdf/2608.03091)). The sensible role for an LLM is a paraphrasing layer over structured, already-computed reasons, never the source of the ranking.

## Group directions: five contract strategies, two that need real design

The group contract fixes the inputs (member vectors, weights, an n×n closeness matrix) and a `strategy` enum. It returns group and per-member scores plus an explanation. The open question is what each strategy should mean and what sits behind it as it matures. Masthoff's foundational user studies show that people choosing for a group use **Average, Average-Without-Misery and Least Misery**, normalise ratings, penalise misery, and value each member's favourite appearing. Viewing order also changes later ratings ([Aberdeen Pure](https://abdn.elsevierpure.com/en/publications/group-modeling-selecting-a-sequence-of-television-items-to-suit-a/)).

| Contract strategy / direction | Literature basis | Pros | Cons for FilmFuse |
|---|---|---|---|
| `mean` (additive utilitarian) | Masthoff 2004; Baltrunas rank aggregation | Simple, well accepted; good for similar groups | Hides strong dislikes |
| `least_misery` | Masthoff 2004 | Matches movie-night "nobody hates it" norm; truthful explanation | Bland picks; one picky member dominates |
| Average-Without-Misery (variant of `least_misery` or `mean`) | Masthoff; Delić & Masthoff tutorial | Floor on misery, then maximise average | Threshold choice unverified; can bury loved items |
| `most_pleasure` | Masthoff (people want each favourite shown) | Gives everyone "their" film | Ignores dislikes; better as a slot rule than a scoring rule |
| Borda / rank-based (possible option inside `mean`) | Baltrunas; AAU thesis | Robust to members using the scale differently | Discards score magnitude |
| `closeness_weighted` | Delić UMAP 2018; Gartrell 2010 | Uses unique FilmFuse signal | Naive centrality weighting contradicts evidence |
| `fairness`: list-level (GFAR) | Kaya, Bridge & Tintarev 2020 | No history needed; strongest for divergent groups | Greedy re-rank more complex to explain |
| `fairness`: sequential (SDAA family) | Stratigi et al. 2020–21 | Uses persistent-group history of picks and votes | Needs a per-member satisfaction ledger |
| Learned aggregators (AGREE, GroupIM, ConsRec) | SIGIR 2018/2020, WWW 2023 | Learn influence and consensus | Need group logs; opaque against a user-chosen strategy |
| LLM as aggregator | Waterschoot et al. 2025 | Flexible | Ignores the requested rule; unreliable explanations |

### Mean, least misery and most pleasure are cheap and well understood

All the classic strategies run in microseconds over a few hundred candidates, so the 5 s budget is not a constraint. Their known failure modes complement each other. Rank aggregation sometimes made group recommendations *more* effective than individual ones on MovieLens synthetic groups, with results depending on group size and member similarity ([Baltrunas et al.](https://typeset.io/pdf/group-recommendations-with-rank-aggregation-and-53rpqq0aaw.pdf)). Perceived fairness of a strategy **depends on how preferences are distributed within the group**, so no single rule is fair for every group ([Springer UMUAI 2024](https://link.springer.com/article/10.1007/s11257-023-09363-0)).

The practical implication is that whatever the user selects as `strategy`, the per-member scores the contract returns should be on a comparable scale. Masthoff's participants normalised ratings, and a rank-based internal step is one way to get that robustness. Masthoff's finding about favourites suggests `most_pleasure` works better as a diversification rule ("one slot per member's top pick") than as a ranking rule.

### Closeness should probably not mean "the central friend wins"

This is the most important evidence-backed design warning in the group notes. In Delić et al.'s study of 41 real groups, **a member's social centrality was "hardly an indicator" of their influence on the decision**. Central members and members of close groups were, however, significantly *happier* with the outcome ([Utrecht portal](https://research-portal.uu.nl/en/publications/how-to-use-social-relationships-in-group-recommenders-empirical-e/)). A related study of 55 groups found that **initial preference conflict barely affected final satisfaction**. Choice satisfaction, task difficulty and conflict style mattered more, and "avoiders" dropped sharply in satisfaction when they lost ([TU Delft](https://repository.tudelft.nl/record/uuid:b976ad52-70b1-4261-b7c4-aa9365907096)).

A row-sum-centrality weighting would therefore model influence wrongly and systematically under-serve peripheral members, who are already the least satisfied. The owner has three defensible alternatives. Mean pairwise closeness can set group-level parameters: close groups tolerate averaging, loose or ephemeral groups get a stronger misery guard. Weighting can be inverted to protect loosely connected members. Or tie strength can select the strategy, in the style of Gartrell et al. ([GROUP 2010](https://home.cs.colorado.edu/~rhan/Papers/Group2010_submitted-camera-ready.pdf)). All of this evidence comes from tourism, not movies.

### Fairness is a property of a list or a history, not an item

Kaya, Bridge and Tintarev show that item-independent aggregation **cannot guarantee fairness across a list**. Their greedy GFAR balances member relevance at every prefix of the top-N, and is strongest for large groups with divergent interests ([TU Delft](https://repository.tudelft.nl/record/uuid:979613f4-ed82-400d-9b9d-08fc7a84c7cc)). For persistent groups, Stratigi et al. track per-member satisfaction across rounds and treat disagreement as the spread between members. Their SDAA shifts between Average and Least Misery based on past disagreement, and they argue plain Average and Least Misery work poorly sequentially ([Tampere](https://trepo.tuni.fi/bitstream/10024/133700/1/Sequential_group_recommendations_based_on_satisfaction_and_disagreement_scores_2021.pdf)).

FilmFuse's votes and picks are exactly the per-round signal these methods need. Note, though, that the ML service is read-only and stateless, so any satisfaction ledger must be computed by core-api and passed in, or derived from read-only tables. RL layers that choose the aggregator each round, such as SQUIRREL ([CEUR](https://ceur-ws.org/Vol-3462/TADA8.pdf)), need many rounds of feedback and are premature.

### Learned and LLM aggregators are later or never

AGREE learns member weights with attention ([arXiv 1804.04327](https://arxiv.org/abs/1804.04327v3)). GroupIM targets ephemeral groups and reports **31–62% relative ranking gains** over earlier group recommenders ([arXiv 2006.03736](https://arxiv.org/abs/2006.03736)). ConsRec models consensus from three views ([arXiv 2302.03555](https://arxiv.org/abs/2302.03555v1)). All three train on group-interaction logs, and their benchmarks (Mafengwo travel, CAMRa2011 households) differ from friend groups. Learned weights also sit awkwardly beside a strategy the user picked explicitly.

LLM aggregation is worse for a contract-driven system. Prompted with member ratings, small open LLMs **mostly behaved like averaging regardless of group structure**, degraded as candidate counts rose, and produced explanations that invoked undefined criteria such as popularity thresholds in up to 76% of cases ([arXiv 2507.13705](https://arxiv.org/html/2507.13705v1)). Asked explicitly to apply least misery or most pleasure, adherence degraded with complexity ([arXiv 2505.05016](https://arxiv.org/html/2505.05016v1)). An LLM aggregator would silently break the `strategy` field.

### Group explanations carry a privacy cost

Najafian, Tintarev et al. found that privacy concern about group explanations depends strongly on **being in the minority preference and on the relationship type** ([TU Delft](https://repository.tudelft.nl/record/uuid:d5ea8883-d081-4d4d-9c25-badc0e48fd79)). Waterschoot et al. found that **the aggregation strategy affected understanding more than explanation detail, and more detail did not help** ([arXiv 2505.04273](https://arxiv.org/abs/2505.04273)).

The safe default is aggregate, non-attributing phrasing in the shared view ("no one in the group strongly dislikes this genre"). Per-member reasons belong only on that member's own device. The contract's single `explanation: str` per movie is frozen, so these privacy rules are most easily enforced by keeping the shared string non-attributing. The per-member scores already in the response can drive self-only explanations client-side. If the contract is ever revised, a structured explanation (template id, cited items or attributes, audience) would make faithfulness testable and the privacy rules centralised.

## The harness, serving and calibration choices matter as much as the model

### The offline harness has a leakage hole and no cold-start view

The existing MovieLens harness already avoids the biggest trap: it ranks against the full catalog. Krichene and Rendle showed that sampled metrics **do not preserve relative comparisons between methods** ([Google Research](https://research.google/pubs/on-sampled-metrics-for-item-recommendation/)). Its per-user temporal split still leaks across the global timeline, though. Ji et al. showed that this lets models recommend items from the future and changes how models rank against each other ([arXiv 2010.11060](https://arxiv.org/abs/2010.11060v4)). A global time cutoff, or item statistics recomputed before each user's test start, closes that hole. Reporting both splits for a while shows whether method rankings flip.

MovieLens users rate many films at signup, so its accuracy overstates FilmFuse's cold-start quality. Truncating training profiles to the first 5, 10 or 20 ratings gives the cold-start view that matters most. Harper and Konstan document the context and limits of MovieLens itself ([GroupLens](https://grouplens.org/blog/movielens-datasets-context-and-history/)).

The 44 synthetic personas are behavioural and contract tests ("a vetoing member never gets their dislikes", "the cited film exists in the user's history and contributes to the score"), not accuracy evidence. Synthetic groups are similarly fragile: **the similarity metric used to build them strongly changes algorithm rankings** ([IOS Press](https://ebooks.iospress.nl/pdf/doi/10.3233/FAIA220324)). Group results should therefore be stratified by cohesion. Real-group work shows behaviour that synthetic averaging misses ([arXiv 1707.09790](https://arxiv.org/pdf/1707.09790)).

### On-request scoring fits easily in 5 s; precompute only item-side artefacts

pgvector HNSW reached about **1.5 ms per query at about 58k 1536-d vectors, against 650 ms for a sequential scan** ([Tembo](https://temboio.substack.com/p/vector-indexes-in-postgres-using)). At FilmFuse's dimensions, an exact in-memory NumPy dot product over 100k × 64 items (about 25 MB) is probably tens of milliseconds. That is my unbenchmarked estimate, and it would make filtering trivial with no recall tuning.

If pgvector is used, its README warns that **filters are applied after the approximate index scan**: at 10% selectivity with default `ef_search=40`, only about 4 rows match ([pgvector](https://github.com/pgvector/pgvector)). Exclude-seen and genre filters therefore need iterative scans and a higher `ef_search`. Core-api enforcing comfort filters downstream compounds this, so the ML service should return more candidates than the slate size.

Per-user precomputed lists go stale on every rating, and the read-only service cannot write them. Group lists cannot be precomputed at all. Item-side artefacts (embeddings, neighbour lists, popularity, genre distributions) should be precomputed and versioned immutably by `model_version`, with user vectors folded in on request.

### Calibration is the cheapest quality and diversity win

Steck's calibration re-ranks a shortlist so its genre mix matches the user's history. His example is that a 70/30 romance/action user should not get a 100% romance list. The re-rank greedily trades relevance against KL miscalibration with a (1−1/e) guarantee ([Steck slides](https://www.slideshare.net/slideshow/calibrated-recommendations/118205343)). Jensen–Shannon divergence avoids KL's infinities ([arXiv 2008.08551](https://arxiv.org/pdf/2008.08551)). Popularity calibration applies the same idea to niche tolerance ([arXiv 2007.12230](https://arxiv.org/abs/2007.12230)).

Calibration needs an interpretable attribute space, which FilmFuse's genre+mood dims supply. It is a post-hoc step, so it is model-agnostic and survives every later model upgrade. For groups, calibrating against a blend of member distributions is a natural extension.

## A phased path: what to build, log and wait for

The upgrade triggers below are judgement calls based on what each method needs. No source gives user-count thresholds for FilmFuse's regime, so the owner should treat them as harness-gated decisions, not numbers to hit.

| Phase | Single-user | Group | Evaluation & logging | Upgrade trigger to next phase |
|---|---|---|---|---|
| **0 — Now (pre-launch)** | Popularity prior + linear content scoring on interpretable dims; derive item mood features; dismissals as negatives, favourites as positives; templated, faithful explanations | `mean`, `least_misery`, `most_pleasure` as specified; Average-Without-Misery as a variant; `closeness_weighted` designed around group-level closeness or protection, not centrality; GFAR-style list-level `fairness` | Fix global-timeline leakage; add cold-start truncation; persona tests for behaviour and explanation faithfulness; guard test against sampled metrics; add calibration and diversity metrics | Harness and persona suite stable; real users arriving |
| **1 — Launch (sparse data)** | Steck calibration re-rank; optional overview-embedding candidate generator; adaptive (bandit-style) calibration swipes using pairwise cards | Non-attributing shared explanations; per-member reasons self-only | Impression log per slate (request id, model version, ordered list with scores, explanation template, arm, display probability); events table joined on request id; group session log (members, closeness, candidates, votes, pick, post-session thumbs); small ε-explore slot with recorded probabilities | Enough co-rated items that item-kNN beats content-only on the harness *and* on a FilmFuse holdout |
| **2 — Growing (co-ratings exist)** | Item-kNN for "because you rated X" explanations; MF/iALS folded into the latent part; nightly or weekly batch retrain gated by harness and personas | Sequential `fairness` with a per-member satisfaction ledger for persistent groups; strategy comparisons using real session outcomes | Team-draft interleaving for single-user ranker comparisons (randomise per user or group); offline replay of logged slates | Hundreds of logged group sessions with picks; latent model clearly beats interpretable-only offline and online |
| **3 — Mature** | Learned hybrid (LightFM-style or learned two-tower) if the harness shows gains; LLM only to paraphrase grounded reasons | Learned attention aggregator (AGREE/GroupIM-style) offered as an extra option, not a replacement for the user's choice | Off-policy estimates from logged propensities | — |

Logging is the one item to do before anything else, because it cannot be added retroactively. Interleaving reportedly needs **about 145× less data than A/B testing** to reach significance, though that figure comes from web search, not recommenders ([Schuth et al.](https://microsoft.com/en-us/research/wp-content/uploads/2016/02/fp041-schuthA.pdf)). At FilmFuse's traffic, that sensitivity is the difference between learning something and learning nothing. Interleaving only identifies which ranker users prefer, not business outcomes ([MetricGate](https://metricgate.com/blogs/interleaving-experiments-ranking-evaluation/)). Because the ML service is read-only, it should return `request_id` and `model_version` and let core-api write the log.

The group session log is the most strategically valuable asset. FilmFuse's live votes and picks give it the real-group ground truth that most of the literature lacks. That log is also the prerequisite for any phase-3 learned aggregator.

## Key papers to read, ranked

**Single-user recommender**

| Rank | Paper | Why it matters for FilmFuse |
|---|---|---|
| 1 | Christakopoulou, Radlinski & Hofmann, "Towards Conversational Recommender Systems," KDD 2016 ([MSR](https://www.microsoft.com/en-us/research/publication/towards-conversational-recommender-systems/)) | Bandit onboarding; +25% after 2 questions; blueprint for adaptive swipes |
| 2 | Rendle, Krichene, Zhang & Anderson, "NCF vs MF Revisited," RecSys 2020 ([arXiv](https://arxiv.org/abs/2005.09683)) | Argues for dot-product scoring, which matches pgvector and the latent part |
| 3 | Dacrema, Cremonesi & Jannach, "Are We Really Making Much Progress?" RecSys 2019 ([DeepAI](https://deepai.org/publication/are-we-really-making-much-progress-a-worrying-analysis-of-recent-neural-recommendation-approaches)) | Why tuned baselines first |
| 4 | Steck, "Calibrated Recommendations," RecSys 2018 ([slides](https://www.slideshare.net/slideshow/calibrated-recommendations/118205343)) | Cheap re-rank over interpretable dims |
| 5 | Decision-tree / attribute elicitation, 2025 ([arXiv 2510.27342](https://arxiv.org/html/2510.27342v1)) | Attribute and pairwise elicitation beat item ratings |
| 6 | Rendle et al., "Revisiting the Performance of iALS," 2021 ([ar5iv](https://ar5iv.arxiv.org/html/2110.14037)) | The CF model to adopt when data arrives |
| 7 | Steck, "Embarrassingly Shallow Autoencoders," WWW 2019 ([arXiv](https://arxiv.org/pdf/1905.03375)) | Strong linear item-item baseline for the harness |
| 8 | Hou et al., "LLMs are Zero-Shot Rankers," ECIR 2024 ([summary](https://papers.lunadong.com/paper/4647)) | Limits of LLM reranking (position bias) |

**Group recommender**

| Rank | Paper | Why it matters for FilmFuse |
|---|---|---|
| 1 | Masthoff, "Group Modeling," UMUAI 2004 ([Aberdeen](https://abdn.elsevierpure.com/en/publications/group-modeling-selecting-a-sequence-of-television-items-to-suit-a/)) | Origin of mean, least misery and most pleasure; order effects |
| 2 | Delić et al., "How to use social relationships in group recommenders," UMAP 2018 ([Utrecht](https://research-portal.uu.nl/en/publications/how-to-use-social-relationships-in-group-recommenders-empirical-e/)) | Decides how `closeness_weighted` should work |
| 3 | Kaya, Bridge & Tintarev, "GFAR," RecSys 2020 ([TU Delft](https://repository.tudelft.nl/record/uuid:979613f4-ed82-400d-9b9d-08fc7a84c7cc)) | List-level `fairness` with no history |
| 4 | Stratigi et al., "Sequential group recommendations based on satisfaction and disagreement," JIIS 2021 ([Tampere](https://trepo.tuni.fi/bitstream/10024/133700/1/Sequential_group_recommendations_based_on_satisfaction_and_disagreement_scores_2021.pdf)) | Sequential fairness for persistent groups |
| 5 | Waterschoot, Tintarev & Barile, "Consistent Explainers or Unreliable Narrators?" RecSys 2025 ([arXiv](https://arxiv.org/html/2507.13705v1)) | Why not to let an LLM aggregate |
| 6 | Najafian et al., privacy concern in group explanations, UMAP 2021 ([TU Delft](https://repository.tudelft.nl/record/uuid:d5ea8883-d081-4d4d-9c25-badc0e48fd79)) | Explanation privacy rules |
| 7 | Sankar et al., "GroupIM," SIGIR 2020 ([arXiv](https://arxiv.org/abs/2006.03736)) | Best-fit learned model for ephemeral groups, for later |
| 8 | Baltrunas et al., group recommendation with rank aggregation ([copy](https://typeset.io/pdf/group-recommendations-with-rank-aggregation-and-53rpqq0aaw.pdf)) | Synthetic-group MovieLens protocol behind the harness |

**Cross-cutting evaluation**

| Rank | Paper | Why it matters for FilmFuse |
|---|---|---|
| 1 | Ji, Sun, Zhang & Li, "A Critical Study on Data Leakage in Recommender System Offline Evaluation," TOIS 2023 ([arXiv](https://arxiv.org/abs/2010.11060v4)) | The leakage hole in the current split |
| 2 | Krichene & Rendle, "On Sampled Metrics for Item Recommendation," KDD 2020 ([Google](https://research.google/pubs/on-sampled-metrics-for-item-recommendation/)) | Why to keep full-catalog ranking |
| 3 | Tintarev & Masthoff, explanation aims, 2012 ([PDF](https://abdn.elsevierpure.com/files/20864620/USER700.pdf)) | What each explanation is for |
| 4 | Waterschoot et al., group explanation understanding, UMAP 2025 ([arXiv](https://arxiv.org/abs/2505.04273)) | Strategy matters more than explanation detail |

## Conclusion

The literature points FilmFuse towards a counter-intuitive allocation of effort. The parts usually treated as scaffolding (item mood features, onboarding question design, a leak-free harness, slate and session logging, faithful templated explanations) carry more expected value than any ranking model, and the frozen contract's interpretable vector is an asset rather than a limitation. Its biggest design risk is not on the single-user side. It is `closeness_weighted`: the intuitive implementation encodes a social-influence model that real-group evidence contradicts and that would quietly disadvantage the least-connected friend.

FilmFuse also has an unusual opportunity. Group-recommender research is starved of real group decisions, and every FilmFuse movie night produces one: candidates shown, votes, the final pick, closeness, and a post-pick satisfaction signal. If those are logged from day one, the owner's later choices between strategies, and any eventual learned aggregator, can be made on FilmFuse's own evidence instead of tourism studies and synthetic MovieLens groups. The main unknowns are how content-only and CF compare at hundreds-of-users scale, and whether synthetic personas or synthetic groups predict real satisfaction. Only that log can settle them.

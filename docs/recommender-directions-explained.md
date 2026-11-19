# Recommender directions, explained

This is a companion to `docs/recommender-directions.md`. That document is dense and assumes recommender-systems background. This one explains the background first (Part 1), then goes through the original section by section in plain language (Parts 2–5), using FilmFuse's own personas and data as examples.

The toy numbers below are made up to illustrate the ideas. They are not proposals for the real algorithm.

**Contents**
- **Part 1 — Background you need**
  - 1.1 What a recommender actually does
  - 1.2 Feedback: explicit, implicit, positive, negative
  - 1.3 Vectors and the dot product (the core trick)
  - 1.4 The cold-start problem
  - 1.5 Content-based filtering
  - 1.6 Collaborative filtering (CF)
  - 1.7 Embeddings
  - 1.8 Neural, graph and LLM recommenders
  - 1.9 Exploration vs exploitation (bandits)
  - 1.10 How recommenders are evaluated
  - 1.11 Group recommendation basics
- **Part 2 — The single-user directions, section by section**
- **Part 3 — The group directions, section by section**
- **Part 4 — Harness, serving and calibration**
- **Part 5 — The phased path, decoded**
- **Glossary**

---

# Part 1 — Background you need

## 1.1 What a recommender actually does

A recommender answers one question over and over: **"Of the movies this person hasn't seen, which ones should be shown first?"**

That breaks into stages, and almost every real system has some version of them:

1. **Candidate generation (retrieval).** Shrink 150,000 movies to a few hundred plausible ones, cheaply. In FilmFuse, core-api already does part of this: it builds the `candidateMovieIds` pool after applying comfort filters (G cap, content limits), excluding seen and dismissed movies, and so on.
2. **Scoring / ranking.** Give each candidate a score for this user (or group), and sort. This is the ML service's main job: `recommend_for_user` and `recommend_for_group`.
3. **Re-ranking.** Adjust the sorted list for things a pure score ignores: variety, balance between genres, fairness between group members, not showing five sequels in a row. Calibration (Part 4) and GFAR fairness (Part 3) live here.
4. **Explanation.** Say *why* each movie is there ("because you rated *Alien* 9/10").

When the document says "the ranker", it means step 2. When it says "the model matters less than you'd think", it means step 2 is less important than the data going in, steps 3–4, and how you measure the results.

## 1.2 Feedback: explicit, implicit, positive, negative

Recommenders learn from **feedback**: signals about what someone likes.

- **Explicit feedback** is the user telling you directly: FilmFuse's 1–10 ratings, the genre and mood sliders, favourites.
- **Implicit feedback** is inferred from behaviour: clicks, watch time, adding to a list. FilmFuse's implicit signals are session votes, session picks (the group watched it), "haven't seen" swipes, and dismissals.

Two consequences matter for FilmFuse:

- **Most research is built for implicit data.** Big platforms have billions of clicks and few ratings. A lot of the top-N literature therefore turns ratings into "interacted / didn't interact" (**binarising**). This throws away the difference between a 3/10 and a 10/10, so FilmFuse needs to decide how to use the full scale. The usual compromise is **confidence weighting**: treat a 9 as a strong positive and a 6 as a weak one.
- **Negative signals are rare and valuable.** A 2/10 rating or a "not interested" dismissal tells you more than silence. Silence might just mean "never heard of it". The document's advice to "treat dismissals as explicit negatives and favourites as strong positives" follows from this.

## 1.3 Vectors and the dot product (the core trick)

Most recommenders boil down to this:

> Describe each **user** as a list of numbers (a vector), describe each **movie** as a list of numbers in the same order, and score the pair by multiplying position by position and adding up. That's the **dot product**.

A toy example with three dimensions (Animation, Horror, Drama):

| | Animation | Horror | Drama |
|---|---|---|---|
| Disney mom (user) | 1.0 | −0.9 | 0.2 |
| *Coco* (movie) | 1 | 0 | 1 |
| *Hereditary* (movie) | 0 | 1 | 1 |

- Disney mom · *Coco* = 1.0×1 + (−0.9)×0 + 0.2×1 = **1.2**
- Disney mom · *Hereditary* = 1.0×0 + (−0.9)×1 + 0.2×1 = **−0.7**

*Coco* wins. That's all a "dot-product recommender" is. Everything else is about **where the numbers come from**:

- **Interpretable dimensions:** each position means something a human can name ("Horror", "cozy"). FilmFuse's contract already has this: the `interpretable` vector is 19 genre dims followed by 8 mood dims, in fixed order. You can explain any score by pointing at the dimensions that contributed most.
- **Latent dimensions:** the positions are learned by a model and have no fixed meaning. Dimension 17 might loosely mean "quirky indie", or nothing nameable. They are usually more accurate once there's enough data, and hard to explain. The contract's optional `latent` field exists for this.

Why everyone likes the dot product:
- It's fast: one multiply-add per dimension.
- Databases can search it efficiently. pgvector's inner-product index finds the highest dot products without checking every movie.
- Rendle et al. 2020 showed that fancier "learned similarity" functions don't beat a well-tuned dot product (more in 1.8).

## 1.4 The cold-start problem

**Cold start** means not having data yet. It comes in two forms:

- **New user cold start:** someone just signed up and has rated nothing. You can't learn their taste from ratings they haven't given.
- **New item cold start:** a movie came out last week and nobody on the platform has rated it.

FilmFuse is **cold-start heavy** on both fronts. It's a new app, so *most* users are new and the whole rating matrix is sparse. TMDB adds new movies all the time.

That's why the document keeps steering towards approaches that work **without** other users' ratings (content-based, stated preferences, onboarding questions) and treats approaches that need lots of overlapping ratings (collaborative filtering) as a later phase.

## 1.5 Content-based filtering

**Content-based** means scoring a movie by its *properties* (genres, keywords, plot, cast, era) against what the user likes.

- **Pros:** works from day one for both new users (use their onboarding sliders) and new movies (TMDB metadata exists on release day). Explanations are honest: "it's a Horror + Mystery film and you rated those genres 9 and 8".
- **Cons:** it can only recommend "more of the same kind of thing". It can't discover that people who love *Paddington* also tend to love *The Grand Budapest Hotel*, because nothing in the metadata links them. Its accuracy ceiling is lower than collaborative filtering once data exists.

Some content representations the document mentions:

- **Genre multi-hot:** a 19-long vector with 1 where the movie has that genre. Cheap and crude.
- **TF-IDF over keywords:** TMDB gives keywords like "time travel", "haunted house", "coming of age". **TF-IDF** (term frequency × inverse document frequency) weights each keyword by how distinctive it is. "Murder" is common and gets a low weight; "time loop" is rare and gets a high one. The result is a long, sparse vector per movie.
- **Sentence embeddings:** run the plot overview through a small language model that outputs, say, 384 numbers capturing meaning. Two movies with similar plots get nearby vectors even if they share no keywords. See 1.7.
- **The mood problem:** TMDB has no "cozy" or "tearjerker" field, but FilmFuse's vector has 8 mood dimensions. To score movies on mood you must *derive* mood features for 74k–150k movies: map keywords to moods, have an LLM tag them in a batch job, compare plot embeddings to a "cozy" prototype, or crowd-source. The document calls this "the single biggest quality lever" for the content approach. Today the mood half of every user vector has nothing to match against.

## 1.6 Collaborative filtering (CF)

**Collaborative filtering** ignores what movies *are* and learns only from *who liked what*. The intuition: people who agreed in the past will agree in the future.

Picture the **rating matrix**: one row per user, one column per movie, the rating in each cell, and mostly empty. CF fills in the blanks.

### Item-kNN (item-based nearest neighbours)

1. For every pair of movies, measure how similarly users rated them, e.g. cosine similarity of their rating columns. *Alien* and *Aliens* end up similar because the same people rate both highly.
2. For each movie, keep its top-k most similar movies (its **neighbours**).
3. To score a candidate for a user, look at the user's ratings of that candidate's neighbours.

The big advantage is a **faithful explanation**: "Recommended because you rated *Alien* 9/10, and people who loved *Alien* loved this." That's literally how the score was computed. It needs overlapping ratings to exist, though.

### Matrix factorisation (MF) and ALS / iALS

MF learns a **latent vector** for every user and every movie (say 64 numbers each) so that user · movie ≈ the rating. The ratings you have train the vectors, and the dot product then predicts the blanks.

- **ALS (Alternating Least Squares)** is a way to train MF: fix the movie vectors and solve for the best user vectors, then fix the users and solve for the movies, and repeat. Each step is a simple least-squares problem.
- **iALS (implicit ALS)** is the version for implicit/binarised data, where "unobserved" counts as weak negative evidence. The document notes its cost grows with the **cube of the vector size d**, so keep d small (e.g. 32–64).
- **Folding in:** when a new user rates a few movies, you don't retrain everything. You keep the movie vectors fixed and solve for just that user's vector, which is fast. That's how a latent vector can live in the contract's `latent` field and update on each rating.
- **Versioning:** latent dimensions mean nothing outside the model that trained them. If you retrain, dimension 17 now means something else, so every stored user latent vector must be re-derived against the new movie vectors. Hence the emphasis on `model_version`.

### EASE

EASE (Steck 2019) learns an **item × item weight matrix** directly ("how much does liking movie A predict liking movie B"), with one closed-form solve. It's very accurate on benchmarks. The catch is that the matrix is items × items: for 74k movies that's 74,000² ≈ 5.5 billion numbers ≈ **22 GB**, and for 150k it's ≈ **90 GB**. It's only usable if restricted to the few thousand movies that actually have ratings.

### LightFM

A hybrid that represents each movie as the sum of its *features'* latent vectors (genres, keywords) plus its own. It's conceptually exactly "content + CF in one model", which is why it keeps coming up. The upstream project is no longer maintained, so you'd use a fork or reimplement it.

### Why CF is "a later layer" for FilmFuse

Every CF method needs **co-ratings**: the same movies rated by many users. With 50 users each rating 20 movies out of 100,000, almost no two users overlap, and CF learns noise. Popularity and content-based scoring beat CF until the matrix fills in.

## 1.7 Embeddings

An **embedding** is a learned vector that places similar things near each other. MF's latent vectors are embeddings learned from ratings. Sentence-embedding models (e.g. `all-MiniLM`, `bge`, `e5`) produce embeddings from text, so movie plots with similar meaning get close vectors.

Practical facts from the document:
- 150,000 movies × 384 numbers × 4 bytes ≈ **230 MB**. pgvector stores that comfortably.
- Plot similarity ≠ preference. Two movies can have similar plots and very different tone or quality. Embeddings are good for finding **candidates** ("films like the ones you loved") and weaker as the final score or the explanation.

## 1.8 Neural, graph and LLM recommenders

These dominate papers, and the document is sceptical of them for FilmFuse. Here's what each is and why.

- **Two-tower models:** one neural network turns a user into a vector, another turns a movie (with its metadata) into a vector, and the score is their dot product. It's MF with richer inputs, and it needs lots of training data.
- **Sequential models (SASRec, BERT4Rec):** treat a user's history as a sequence, like words in a sentence, and predict the next item with transformer networks. They're great for "what will you click next" on shopping or video sites. Movie ratings are few and slow (a few a week), and the order you rate movies in carries little signal.
- **Graph neural networks (LightGCN, GraphSAGE):** treat users and movies as nodes in a graph and pass information along edges ("users who rated X also rated Y"). LightGCN does best for **high-interaction users**, the opposite of FilmFuse's situation. The cheap, useful graph idea is **one-hop friend smoothing**: blend in what your friends rated highly. It's explainable ("3 friends loved this"), but nobody has benchmarked it on small friend graphs.
- **LLM recommenders:** ask a large language model to rank candidates from a description of the user. They can match simple baselines when re-ranking a short list, but:
  - **Position bias:** the model tends to favour items listed first, so you have to shuffle and re-ask.
  - **Hallucination:** they invent titles.
  - **Popularity bias:** they favour famous films.
  - **Unfaithful explanations:** the stated reason isn't how the ranking was actually made.
  - **Cost and latency:** every call costs money and time.

  The recommended role is narrow: **paraphrase an explanation that was already computed** (turn "genre:27 +3.1, similar_to:694" into a friendly sentence), and never decide the ranking.

### The reproducibility crisis (why "tuned simple models beat architecture choice")

This is the most important background for the whole document.

- **Dacrema et al. 2019** tried to reproduce 18 published neural recommenders. Only 7 could be reproduced, and **6 of those 7 lost to simple nearest-neighbour or graph baselines** when the baselines were tuned properly.
- **Rendle et al. 2020** showed that NCF (a famous "neural collaborative filtering" model) loses to a plain, well-tuned dot product.
- On MovieLens, differences between *implementations* of the same model, such as BERT4Rec scoring 0.055 vs 0.156 NDCG depending on whose code you run, are bigger than the differences between *different* models.

**Lesson:** a new paper claiming +5% usually compared against an untuned baseline. For FilmFuse, a well-tuned simple model plus good data beats a fashionable model.

## 1.9 Exploration vs exploitation (bandits)

If you only ever show what you predict someone likes (**exploitation**), you never learn anything new about them. Show things just to learn (**exploration**) and you annoy them. Balancing the two is the **multi-armed bandit** problem, named after a gambler choosing between slot machines.

- **LinUCB:** score each option as *predicted score + an uncertainty bonus*. Options you know little about get a boost, so you try them sometimes. It needs a small per-user matrix (dimension × dimension; ~27×27 for FilmFuse's interpretable vector).
- **Thompson sampling:** sample a plausible taste vector from your current uncertainty, and pick the best option for that sample. It explores naturally.

**Why this matters for FilmFuse:** the calibration swipe deck is a perfect bandit. Instead of showing random or popular movies, show the movie that would teach you the most about this user, e.g. "we don't know if Meg tolerates slow-burn thrillers; show one". Christakopoulou et al. 2016 got **+25% after just 2 well-chosen questions**. Showing **pairs** ("this or that?") also teaches more per swipe than single ratings, because people are better at comparing than at scoring on an absolute scale.

## 1.10 How recommenders are evaluated

### Offline evaluation (what `eval/` does)

1. Take historical ratings.
2. **Split** them: train on the past and hide the "future" as a test set.
3. Train the recommender on the train set and ask for a top-k list per user.
4. Check how many hidden test movies the user actually liked appear in the list.

The metrics in the harness:
- **Precision@k:** of the k recommended, what fraction were hits.
- **Recall@k:** of the user's hidden liked movies, what fraction appeared in the top k.
- **Hit rate@k:** did at least one hit appear?
- **NDCG@k** (normalised discounted cumulative gain): rewards hits more the **higher** they appear. A hit at rank 1 counts much more than one at rank 10. It's 1.0 for a perfect ordering.
- **Coverage:** what fraction of the catalogue ever gets recommended to anyone. Low coverage means everyone gets the same blockbusters.
- **Popularity bias:** how much the recommendations lean towards already-popular films.

### Two classic evaluation traps

**Trap 1: sampled metrics.** Many papers save compute by ranking the hidden movie against only **100 random negatives** instead of the full catalogue. Krichene & Rendle (KDD 2020) proved this can **reverse which method looks better**. The FilmFuse harness ranks against the full catalogue, which is correct; keep it that way.

**Trap 2: data leakage across the timeline.** The harness splits **per user**: each user's last 20% of ratings by time is the test set. But users live on different timelines.

> Example: Alice's test ratings are from 2015. Bob's *training* ratings include 2018 ratings of movies released in 2017. The model learns from Bob that the 2017 movie is popular, then "predicts" it for Alice in 2015, before it existed.

Ji et al. (TOIS 2023) showed this leakage **changes which models look best**. The fix is a **global time cutoff**: everything before date T is training, everything after is test, for everyone.

### Cold-start evaluation

MovieLens only includes users with 20+ ratings, but FilmFuse users will mostly have 3–20. A model that wins on rich MovieLens profiles might lose on thin FilmFuse profiles. The fix is to **truncate** each test user's training profile to their first 5, 10 or 20 ratings and report results per level.

### Online evaluation

Offline metrics only predict what real users will do. Online you measure real behaviour:
- **A/B test:** half the users get ranker A and half get B, then compare outcomes. It needs a lot of users to see a difference.
- **Interleaving:** for each user, **merge** A's and B's lists into one (alternating picks, the "team draft"), then see whose items get clicked or watched. Each user compares both rankers at once, which is reportedly up to ~145× more sensitive than A/B testing (evidence from web search). At FilmFuse's scale, that may be the only way to learn anything.
- **Logging propensities:** record how likely each item was to be shown. Later you can estimate how a *different* ranker would have done on old logs (**off-policy evaluation**).

## 1.11 Group recommendation basics

A group recommender has an extra step: combining several people's preferences into one list. This is **social choice** (the maths of voting) applied to movies.

### Two ways to combine

- **Profile aggregation:** merge the members' taste vectors into one "group person" vector, then rank like a single user. It's cheap, but it can produce a vector that matches nobody: averaging a Disney fan and a horror fan gives someone who mildly likes animated horror.
- **Score / rank aggregation:** score each movie **for each member separately**, then combine the scores. This is what FilmFuse's contract implies, since it returns `memberScores` per movie. Most of the literature prefers it because it keeps individual dislikes visible.

### The classic strategies, worked through

Chaos Movie Night (Disney mom, horror fan, film bro), with each member's predicted score out of 10:

| Movie | Disney mom | Horror fan | Film bro | Mean | Least misery (min) | Most pleasure (max) |
|---|---|---|---|---|---|---|
| *Coco* | 10 | 4 | 6 | **6.7** | 4 | **10** |
| *Get Out* | 2 | 9 | 9 | **6.7** | 2 | 9 |
| *Knives Out* | 7 | 7 | 8 | **7.3** | **7** | 8 |
| *Hereditary* | 1 | 10 | 8 | 6.3 | 1 | **10** |

- **Mean** (average): *Knives Out* wins at 7.3. Good in general, but it hides strong dislikes: *Get Out* ties *Coco* even though Meg would hate it.
- **Least misery** (score = the unhappiest member's score): *Knives Out* wins at 7. Nobody is miserable. Its weaknesses: it tends towards bland compromises, and one picky member can veto everything.
- **Most pleasure** (score = the happiest member's score): *Coco* and *Hereditary* tie at 10, which is clearly bad for a group movie night. Masthoff's finding is that people do like seeing *each member's* favourite somewhere in the list, so this works better as a **slot rule** ("include one top pick per member somewhere") than as the ranking score.
- **Average without misery:** drop anything where someone is below a threshold (say 4), then average what's left. *Get Out* and *Hereditary* are out, and *Knives Out* wins. It's the most studied hybrid.
- **Borda count:** each member ranks the movies, and points go by position (4 for 1st, 3 for 2nd, and so on), then the points are summed. It doesn't care that the film bro rates everything 6–9 while the Disney mom uses the full 1–10 range, so it's **robust to people using the scale differently**. It loses the information that someone *hates* a film rather than just ranking it last.

### Fairness

- **Item-level fairness** asks whether *this movie* is fair to everyone. That's least misery, basically.
- **List-level fairness** asks whether *the whole list of 10* is fair: did everyone get something they love somewhere? Kaya et al. showed that scoring items independently **cannot** guarantee this. Their **GFAR** builds the list one position at a time, and at each step prefers movies that help members who haven't been served by the list so far.
- **Sequential fairness (across sessions):** if the horror fan "lost" last Friday, give them more weight this Friday. Stratigi et al.'s **SDAA** family keeps a per-member satisfaction record over rounds. When the group disagreed a lot last time, it leans more towards protecting the unhappy member (least-misery-like); when the group agreed, it leans towards the average.

### Social influence and closeness

Real groups aren't equal. Close friends defer to each other, and some people are more influential. FilmFuse has an n×n **closeness matrix** to capture relationships. The intuitive design is to weight each member by how central or close they are, but the evidence says that's wrong (see Part 3).

### Ephemeral vs persistent groups

- **Persistent groups:** the same people repeatedly (the Alvarez family, Thesis Screening). History accumulates, so sequential fairness and learned models become possible.
- **Ephemeral groups:** a one-off "Tonight" session with whoever's around. There's no group history, only the individuals' histories.

FilmFuse has both, which matters for which methods apply.

---

# Part 2 — The single-user directions, section by section

### "Tuned simple models beat architecture choice on MovieLens"

**What it says:** the reproducibility papers (1.8) show that most "better" models aren't better once baselines are tuned. On MovieLens, a top model and a simple one differ by a few points, e.g. EASE at ~0.39 Recall@20 vs the best at ~0.42. Differences between implementations of the same model are even larger.

**What it means for you:**
- Don't pick a model because a paper says it's state of the art.
- Every model you try must be compared in the harness against **tuned** baselines, including plain popularity (`eval/baselines.py`).
- Nobody has studied which approach wins at a few hundred users. Any claim about that is a guess, so your harness is the referee.

### The options table

The table rates each option on three things FilmFuse cares about: **does it work on day one** (cold start), **are its explanations honest**, and **does it fit a CPU-only, read-only service**. Reading it with Part 1:

- **Popularity:** always the baseline to beat, and boring.
- **Linear content scoring on the interpretable vector:** the user vector (genre + mood sliders, updated by ratings) dotted with movie feature vectors. It works on day one and its explanations are perfect. It's limited by accuracy and by needing movie mood features.
- **TF-IDF keywords, sentence embeddings:** richer content signals, best as candidate generators or extra features.
- **Item-kNN, MF/iALS, EASE:** collaborative filtering (1.6). They need co-ratings first. Item-kNN has the best explanations, MF fits the `latent` field, and EASE is too big for the full catalogue.
- **LightFM:** the ideal hybrid on paper, but unmaintained.
- **Contextual bandit over swipes:** the onboarding and active-learning idea (1.9).
- **Sequential / GNN / LLM:** poor value now (1.8).

### "Content scoring is the only approach that works from the first session"

**Plain version:** FilmFuse's contract already defines the user as an interpretable genre + mood vector, so the obvious first model is "user vector · movie feature vector + a popularity nudge". The document calls this a "hand-built two-tower": the user tower is your stated sliders adjusted by ratings, and the movie tower is its genre/mood features.

**The catch it flags:** the 8 mood dimensions in the user vector have nothing to match on the movie side until you derive movie mood scores. Doing that well (keyword maps, LLM batch tagging, embedding prototypes) is called the biggest quality lever.

**The supporting evidence:** asking users about *attributes* ("do you like slow-burn horror?") rather than only rating individual movies makes later models better. YouTube does attribute onboarding for the same reason: you can't cover a big catalogue by asking about movies one at a time. FilmFuse's genre and mood sliders already are attribute elicitation.

### "Onboarding and the swipe deck deserve more design effort than the ranker"

**Plain version:** the biggest single number in the single-user research isn't about models. It's that choosing *which questions to ask* new users adaptively gave +25% after two questions.

The choice it puts to you:
- **Passive swipe deck:** show popular or top-predicted films and record answers. Simple.
- **Active swipe deck (a bandit):** pick the next card to reduce uncertainty about the user. Use pairwise "this or that" cards where possible. The cost is a small matrix per user. The risk is showing films chosen to *teach the model* rather than to *please the user*, so cap how often that happens.

Two rules apply to both versions: dismissals are strong negatives and favourites are strong positives.

There's also an untested idea: start a brand-new user's vector as a weighted average of their friends' vectors. It's cheap and plausible, but no source measured it.

### "Collaborative filtering is a later layer, and item-kNN is the explainable entry point"

**Plain version:** when overlapping ratings exist, add CF.
- **Start with item-kNN.** Its explanations ("because you rated X 9/10") are true by construction.
- **Then MF/iALS** in the `latent` field, scored with the dot product. It suits pgvector.
- **Costs to remember:** latent vectors must be re-derived every time the model is retrained (versioning, 1.6). iALS cost grows with d³, so keep d small. You'll need to decide how 1–10 ratings become iALS's binary-plus-confidence input.
- **EASE** only works on a subset of the catalogue, roughly the few thousand movies with ratings.
- **LightFM** means a fork or a reimplementation.

### "Neural sequential, GNN and LLM rankers are poor value at this scale"

**Plain version:**
- **Sequence models** want long, fast histories; movie ratings are short and slow.
- **LightGCN** shines for heavy users, the opposite of FilmFuse.
- **The interesting graph idea** is the cheap one: blend in friends' high ratings. It's explainable, and still unbenchmarked.
- **LLMs** re-rank about as well as simple baselines, but they're biased by list order, so use them only to *word* explanations that were already computed.

---

# Part 3 — The group directions, section by section

### The strategy table

Your contract fixes the inputs (member vectors, weights, closeness matrix) and a `strategy` choice, and the response returns a group score, per-member scores and an explanation. The table asks what each strategy *should mean*. With the worked example in 1.11 in mind:

- **`mean`:** simple and widely accepted, and best for similar groups (Thesis Screening). It hides strong dislikes.
- **`least_misery`:** matches the "nobody hates it" norm of movie night and is easy to explain truthfully. It gives bland picks, and one picky member dominates.
- **Average-without-misery:** a floor first, then the average. It could be a variant inside `least_misery` or `mean`. The threshold value is untested.
- **`most_pleasure`:** gives everyone "their" film. It ignores dislikes, so it's better as a slot rule.
- **Borda / rank-based:** robust to different scale habits (the flat-six rater vs the generous rater). It loses magnitude.
- **`closeness_weighted`:** uses FilmFuse's unique social signal. The naive version contradicts the evidence (below).
- **`fairness`, list-level (GFAR):** needs no history and is strongest for divergent groups like Chaos Movie Night. It's harder to explain.
- **`fairness`, sequential (SDAA):** uses past sessions, which suits persistent groups. It needs a satisfaction record per member.
- **Learned models (AGREE, GroupIM, ConsRec):** they learn who influences whom, but need group logs FilmFuse doesn't have yet.
- **LLM aggregator:** ignores the requested strategy (below).

### "Mean, least misery and most pleasure are cheap and well understood"

**Plain version:**
- **Cheap:** these run in microseconds over a few hundred candidates, so the 5 s budget is irrelevant here.
- **No universally fair rule:** whether people *perceive* a strategy as fair depends on how tastes are spread in that group (Barile et al. 2024).
- **Comparable scales:** per-member scores should be on the same footing. If the film bro's model outputs 6–9 and Meg's outputs 1–10, Meg dominates `least_misery` just by scale. Masthoff's study participants normalised scores intuitively, and a rank-based step (Borda-like) is one way to get there.
- **`most_pleasure` as a slot rule:** people like seeing each person's favourite somewhere, which supports "one slot per member's top pick".

### "Closeness should probably not mean 'the central friend wins'"

This is called the most important design warning in the group research, so it's worth unpacking.

**The intuitive design:** sum each member's row of the closeness matrix (how close they are to everyone else) and give more weight to more central members, on the theory that they're the "hub" of the friend group and influence the decision.

**What the evidence says:**
- **Delić et al. (41 real groups):** centrality was **"hardly an indicator"** of influence on the final choice. Central members ended up **happier** anyway.
- **A related study of 55 groups:** how much people disagreed at the start barely affected final satisfaction. What mattered was how the decision *felt* and each person's conflict style. People who avoid conflict were much less happy when they lost.

**So:** weighting by centrality would give *more* to the people who are already happiest, and *less* to the peripheral member (the friend-of-a-friend at movie night), who is already the least satisfied and most likely to quietly avoid conflict.

**The three alternatives offered:**
1. **Closeness sets group-level behaviour:** a tight group (high average closeness) can tolerate averaging, while a loose or one-off group gets a stronger least-misery guard.
2. **Invert the weighting:** protect loosely connected members, giving them *more* weight.
3. **Tie strength picks the strategy:** in the style of Gartrell et al. 2010, close-knit groups use one rule and loose groups another.

**Caveat:** this evidence is from tourism group decisions, not movies.

### "Fairness is a property of a list or a history, not an item"

**Plain version:**
- **List level:** scoring each movie independently can't guarantee everyone gets something they love *somewhere in the top 10*. GFAR fixes that by building the list position by position (1.11).
- **History level:** for persistent groups, track how satisfied each member was in past sessions and adapt. SDAA leans towards protecting the unhappy member when the group disagreed last time.
- **What FilmFuse already has:** its votes and picks are exactly the per-round signal SDAA needs.
- **The architectural constraint:** the ML service is read-only and stateless. It can't keep the "who lost last time" record itself. core-api would compute it and pass it in, or the ML service derives it from read-only tables (`rec_session_votes`, `watch_history`, ratings of picks).
- **Too early:** reinforcement-learning approaches that pick a strategy each round (SQUIRREL) need far more history.

### "Learned and LLM aggregators are later or never"

**Plain version:**
- **Learned models:**
  - **AGREE** uses **attention** to learn how much each member's preference counts in each decision.
  - **GroupIM** is designed for one-off groups and reports big gains on its benchmarks.
  - **ConsRec** models the group's consensus.
  - All three train on **group interaction logs** and were benchmarked on travel and household data, not friend movie nights. A learned weighting also sits awkwardly next to a strategy the user explicitly chose.
- **LLM aggregators:**
  - When asked to aggregate, small open LLMs **mostly just averaged**, whatever strategy they were asked for.
  - They got worse as candidate lists grew.
  - Their explanations cited criteria they weren't given in up to 76% of cases.
  - That would silently break your `strategy` field: the user picks least misery and gets an average.

### "Group explanations carry a privacy cost"

**Plain version:**
- **Who cares about privacy:** concern about explanations that reveal members' preferences is highest for people in the **minority** (the one person who hates horror in the horror club) and depends on the relationship (family vs coworkers).
- **Explanation detail barely helps:** more detail didn't help people understand. The strategy itself mattered more.
- **The rule this suggests:**
  - The **shared** explanation string never attributes tastes to people. Say "no one in the group strongly dislikes this genre", not "Meg hates horror".
  - **Per-member** reasons appear only on that member's own phone. The `memberScores` in the response already allow that client-side.
- **Consistency with what exists:** FilmFuse already applies the same rule to comfort filters, where the banner never says *who* caused the G cap.
- **A possible contract change:** a structured explanation (template id, cited movies or attributes, audience) would make honesty and privacy testable. That's a change to flag before making.

---

# Part 4 — Harness, serving and calibration

### "The offline harness has a leakage hole and no cold-start view"

**Plain version** (background in 1.10):
- **Good:** it ranks against the full catalogue, not 100 sampled negatives. Keep it.
- **Bad:** the per-user time split leaks the future. The fix is a global time cutoff. Report both splits for a while to see whether model rankings change.
- **Missing:** a cold-start view. Truncate users to their first 5/10/20 ratings, because FilmFuse users look like that and MovieLens users don't.
- **The 44 personas** test **behaviour and rules**, not accuracy. Example rules: "a member who hates a genre never gets it pushed in least misery", "the movie cited in an explanation really is in the user's history and really contributed".
- **Synthetic groups are fragile:** how you define "similar" when building them changes which algorithm wins. Report group results separately for cohesive and divergent groups, which the harness already does with `similar` / `dissimilar` / `random`.

### "On-request scoring fits easily in 5 s; precompute only item-side artefacts"

**Background:**
- **ANN (approximate nearest neighbour) search:** finds vectors with the highest dot product without checking all of them, by trading a little accuracy for a lot of speed. **HNSW** is pgvector's best index type: a layered graph you walk towards the best matches.
- **Precompute vs on-request:** you can compute recommendations ahead of time and store them, or compute them when asked.

**What the section says:**
- **Speed is a non-issue:** an exact NumPy dot product over 100k movies × 64 dims (~25 MB in memory) is probably tens of milliseconds. That's an unbenchmarked estimate, but you might not need ANN at all. pgvector HNSW managed ~1.5 ms per query at 58k large vectors.
- **pgvector filter gotcha:** filters are applied *after* the index finds its top matches. Ask for the top 40 with a filter that only 10% of movies pass, and you get about 4 results. Exclude-seen and genre filters need iterative scans or a bigger `ef_search`. Because core-api also drops comfort-filter violations afterwards, the ML service should return **more** candidates than the final list size. The existing candidate-pool design (core-api pre-filters, ML ranks within it) sidesteps most of this.
- **Don't precompute per-user lists:** they go stale on every rating, the read-only service can't store them anyway, and group lists can't be precomputed at all. Precompute **movie-side** things instead (embeddings, neighbour lists, popularity, genre/mood features), versioned by `model_version`, and fold user vectors in on request.

### "Calibration is the cheapest quality and diversity win"

**Background:** a relevance-only ranker over-serves a user's main taste. If Ollie the Pixar crier's history is 70% animation and 30% drama, a pure ranker might give a 100% animation list, because animation scores slightly higher every time. That's accurate per item and wrong as a list.

**Steck's calibration** re-ranks a shortlist so the list's genre mix roughly matches the user's (70/30 here). It builds the list greedily, trading relevance against "how far is the list's genre mix from the user's", measured with KL divergence. Jensen–Shannon divergence is a safer variant, because KL blows up to infinity when a genre's share is zero.

Why it's highlighted:
- It's a post-processing step, so it works with any model and survives every upgrade.
- It needs an interpretable attribute space, which FilmFuse's genre + mood dims already are.
- For groups, calibrate against a blend of members' genre mixes.
- The same idea applies to popularity: someone who likes niche films should get a list with niche films in it.

---

# Part 5 — The phased path, decoded

The table in the original is a suggested order. Its triggers are judgement calls checked against the harness, not hard user-count thresholds.

**Phase 0 — now, before launch**
- **Single-user:** popularity + content scoring on the interpretable vector, movie mood features derived, dismissals and favourites used as strong signals, templated honest explanations.
- **Group:**
  - mean, least misery and most pleasure as defined
  - average-without-misery as a variant
  - `closeness_weighted` designed around group-level closeness or protecting peripheral members
  - `fairness` as GFAR-style list-level
- **Evaluation:**
  - fix the leakage (global cutoff)
  - add cold-start truncation
  - persona tests for behaviour and explanation honesty
  - a test that blocks sampled metrics
  - calibration and diversity metrics
- **Move on when:** the harness and persona tests are stable and real users are arriving.

**Phase 1 — launch, sparse data**
- **Single-user:** calibration re-rank, optionally plot-embedding candidates, and active (bandit) swipes with pairwise cards.
- **Group:** non-attributing shared explanations, with per-member reasons on the member's own device only.
- **Logging** (the critical piece):
  - **Per list shown:** a request id, model version, the ordered list with scores, the explanation template, which experiment arm, and the chance each item had of being shown.
  - **An events table** joined on that request id: ratings, votes, dismissals.
  - **A group session log:** members, closeness, candidates, votes, the pick, and post-session thumbs.
  - **A small exploration slot** with its probabilities recorded.
  - The ML service returns `request_id` and `model_version`, and core-api writes the rows, because the ML service is read-only.
- **Move on when:** item-kNN beats content-only both in the harness and on a held-out slice of FilmFuse's own data.

**Phase 2 — growing, co-ratings exist**
- **Single-user:** item-kNN (with "because you rated X" explanations), then MF/iALS in `latent`, retrained nightly or weekly, and only promoted if the harness and persona tests pass.
- **Group:** sequential fairness with a satisfaction ledger for persistent groups, and strategies compared on real session outcomes.
- **Evaluation:** team-draft interleaving to compare single-user rankers, and replaying logged lists offline.
- **Move on when:** there are hundreds of logged group sessions, and the latent model clearly beats interpretable-only, both offline and online.

**Phase 3 — mature**
- **Single-user:** a learned hybrid (LightFM-style or a learned two-tower) if the harness shows a gain. An LLM only paraphrases computed reasons.
- **Group:** a learned aggregator (AGREE/GroupIM-style) offered as an *extra* strategy, never replacing the user's choice.
- **Evaluation:** off-policy estimates from the logged probabilities.

**Why logging is "the one thing to do before anything else":** you can always add a model later, but you can never go back and record what users were shown last month. Real group decisions are also rare in research: most papers use synthetic groups or tourism studies. Every FilmFuse movie night produces real ground truth (candidates, votes, pick, satisfaction). If it's logged from day one, your later strategy decisions can rest on your own data instead of other people's proxies.

### The ranked reading lists

If you read only three, start with these:
1. **Masthoff 2004:** where mean, least misery and most pleasure come from, and how real people combine preferences.
2. **Christakopoulou et al. 2016:** how to ask new users the right questions (bandit onboarding).
3. **Ji et al. 2023:** the leakage problem in your current harness, and how to fix it.

Next up:
- **Delić et al. 2018:** before designing `closeness_weighted`.
- **Kaya et al. 2020 (GFAR):** before designing `fairness`.
- **Dacrema 2019 + Rendle 2020:** for healthy scepticism about new models.
- **Steck 2018:** for calibration.

---

# Glossary

| Term | Meaning |
|---|---|
| **ALS / iALS** | Alternating Least Squares: trains matrix factorisation by solving for users and items in turn. iALS is the implicit-feedback version. |
| **ANN** | Approximate nearest neighbour search: finds the closest vectors fast, slightly inexactly. |
| **Attention** | A neural mechanism that learns how much weight to give each input, e.g. each group member. |
| **Bandit** | An algorithm that balances trying uncertain options (explore) with using the best known (exploit). |
| **Binarise** | Turn ratings into liked / not-interacted, dropping the scale. |
| **Borda count** | A voting rule where points are given by rank position, then summed. |
| **Calibration** | Re-ranking so a list's genre (or popularity) mix matches the user's history. |
| **Candidate generation** | The cheap first stage that shrinks the catalogue to a few hundred plausible items. |
| **Co-ratings** | The same items rated by many users; what collaborative filtering needs. |
| **Cold start** | Having little or no data about a user or an item. |
| **Collaborative filtering (CF)** | Recommending from patterns in who liked what, ignoring item content. |
| **Confidence weighting** | Treating stronger signals (a 9/10) as more certain positives than weak ones (a 6/10). |
| **Content-based** | Recommending from item properties (genre, keywords, plot) matched to the user's tastes. |
| **Dot product** | Multiply two vectors position by position and sum; the standard user × item score. |
| **EASE** | A linear item-to-item model learned in one closed-form step; accurate but item × item sized. |
| **Embedding** | A learned vector where similar things land close together. |
| **Ephemeral group** | A one-off group with no shared history. |
| **Explicit / implicit feedback** | Stated preferences (ratings) vs inferred from behaviour (watches, votes). |
| **Folding in** | Computing a new user's latent vector against fixed item vectors, without retraining. |
| **GFAR** | A list-level group fairness re-ranker (Kaya, Bridge & Tintarev 2020). |
| **GNN** | Graph neural network: learns by passing information along a graph's edges. |
| **HNSW** | Hierarchical Navigable Small World: pgvector's fastest ANN index type. |
| **Interleaving** | Online test that merges two rankers' lists for each user and credits clicks to each. |
| **Item-kNN** | Score items by their similarity to items the user already rated. |
| **KL / Jensen–Shannon divergence** | Measures of how different two distributions are; used in calibration. |
| **Latent vector** | Learned dimensions with no fixed human meaning. |
| **Leakage** | Test information sneaking into training, making offline results look better than reality. |
| **Least misery / most pleasure** | Group score = the unhappiest / happiest member's score. |
| **LightFM** | A hybrid of content features and CF in one model; unmaintained upstream. |
| **Matrix factorisation (MF)** | Learns user and item latent vectors whose dot products approximate ratings. |
| **Model version** | Identifier tying latent vectors and logs to the model that produced them. |
| **NDCG@k** | A ranking metric that rewards hits more the higher they appear in the top k. |
| **Off-policy evaluation** | Estimating how a different ranker would have done, from logs of the current one. |
| **Persistent group** | The same people repeatedly, so history accumulates. |
| **Popularity bias** | A tendency to recommend already-popular items. |
| **Position bias** | Favouring items because of where they appear in a list (a known LLM ranking flaw). |
| **Precision / recall / hit rate @k** | The share of top-k items that are hits / the share of the user's hits captured / whether any hit appeared. |
| **Profile vs score aggregation** | Merge members' vectors first vs score each member, then combine. |
| **Propensity** | The probability an item was shown; logged to enable off-policy evaluation. |
| **Re-ranking** | Adjusting a scored list for diversity, calibration or fairness. |
| **Sampled metrics** | Ranking against a few random negatives instead of the full catalogue; can mislead. |
| **SDAA** | Sequential group aggregation that adapts between average and least misery based on past disagreement (Stratigi et al.). |
| **Sequential recommender** | Predicts the next item from the order of past interactions (SASRec, BERT4Rec). |
| **TF-IDF** | Weights words by how frequent they are in one document and how rare across all documents. |
| **Two-tower** | Separate networks embed users and items; the score is their dot product. |

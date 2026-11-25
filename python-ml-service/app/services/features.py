"""Movie feature vectors (content part of the hybrid model): one value per interpretable dim.

* Genre dims are one-hot (1.0 when the movie has that TMDB genre).
* Mood dims are derived, because TMDB has no moods. Each mood has a rule: genres that hint at
  it (with a strength) and TMDB keywords that confirm it. The movie's strength for a mood is
  the strongest genre hint, raised when a keyword matches. Strengths are 0-1.

The rule table is plain data so it can be tuned without touching the scoring code. Offline
(MovieLens) movies have genres only, so their moods come from the genre hints alone.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from app.services.recommender import GENRE_IDS, INTERPRETABLE_DIM, MOOD_TAGS

# TMDB genre ids by name, for readable rules.
ACTION, ADVENTURE, ANIMATION, COMEDY, CRIME, DOCUMENTARY, DRAMA = 28, 12, 16, 35, 80, 99, 18
FAMILY, FANTASY, HISTORY, HORROR, MUSIC, MYSTERY, ROMANCE = 10751, 14, 36, 27, 10402, 9648, 10749
SCIFI, TV_MOVIE, THRILLER, WAR, WESTERN = 878, 10770, 53, 10752, 37

GENRE_NAMES: dict[int, str] = {
    ACTION: "Action",
    ADVENTURE: "Adventure",
    ANIMATION: "Animation",
    COMEDY: "Comedy",
    CRIME: "Crime",
    DOCUMENTARY: "Documentary",
    DRAMA: "Drama",
    FAMILY: "Family",
    FANTASY: "Fantasy",
    HISTORY: "History",
    HORROR: "Horror",
    MUSIC: "Music",
    MYSTERY: "Mystery",
    ROMANCE: "Romance",
    SCIFI: "Sci-Fi",
    TV_MOVIE: "TV movies",
    THRILLER: "Thriller",
    WAR: "War",
    WESTERN: "Western",
}

# How explanations name a mood: "Because you love <phrase>".
MOOD_PHRASES: dict[str, str] = {
    "mind-bending": "mind-bending films",
    "cozy": "cozy films",
    "laugh-out-loud": "big laughs",
    "edge-of-seat": "edge-of-your-seat films",
    "tearjerker": "a good cry",
    "visually-stunning": "visually stunning films",
    "dark-gritty": "dark, gritty films",
    "feel-good": "feel-good films",
}


@dataclass(frozen=True)
class MoodRule:
    genres: dict[int, float]  # genre id -> hint strength (0-1)
    keywords: frozenset[str]  # any match confirms the mood


MOOD_RULES: dict[str, MoodRule] = {
    "mind-bending": MoodRule(
        {SCIFI: 0.6, MYSTERY: 0.5, THRILLER: 0.3, FANTASY: 0.2},
        frozenset(
            {
                "time travel",
                "dream",
                "twist ending",
                "surrealism",
                "alternate reality",
                "parallel world",
                "mind control",
                "nonlinear timeline",
                "artificial intelligence",
                "psychological thriller",
                "dystopia",
            }
        ),
    ),  # fmt: skip
    "cozy": MoodRule(
        {FAMILY: 0.6, ANIMATION: 0.5, ROMANCE: 0.3, COMEDY: 0.3},
        frozenset(
            {
                "christmas",
                "holiday",
                "friendship",
                "small town",
                "cooking",
                "family relationships",
                "pets",
                "dog",
                "fairy tale",
                "found family",
            }
        ),
    ),  # fmt: skip
    "laugh-out-loud": MoodRule(
        {COMEDY: 0.8},
        frozenset({"parody", "spoof", "satire", "slapstick", "stand-up comedy", "buddy comedy"}),
    ),
    "edge-of-seat": MoodRule(
        {THRILLER: 0.8, ACTION: 0.6, HORROR: 0.5, CRIME: 0.3, WAR: 0.3},
        frozenset(
            {
                "heist",
                "chase",
                "car chase",
                "survival",
                "hostage",
                "race against time",
                "escape",
                "assassin",
                "slasher",
                "serial killer",
            }
        ),
    ),  # fmt: skip
    "tearjerker": MoodRule(
        {DRAMA: 0.5, ROMANCE: 0.4, WAR: 0.3},
        frozenset(
            {
                "death",
                "grief",
                "cancer",
                "terminal illness",
                "loss of loved one",
                "tragedy",
                "dying and death",
                "loss of father",
                "loss of mother",
                "sacrifice",
            }
        ),
    ),  # fmt: skip
    "visually-stunning": MoodRule(
        {ANIMATION: 0.5, FANTASY: 0.5, SCIFI: 0.5, ADVENTURE: 0.4},
        frozenset(
            {"epic", "space", "3d animation", "superhero", "fantasy world", "nature", "magic"}
        ),
    ),
    "dark-gritty": MoodRule(
        {CRIME: 0.6, HORROR: 0.6, THRILLER: 0.4, WAR: 0.4},
        frozenset(
            {
                "neo-noir",
                "revenge",
                "serial killer",
                "drug addiction",
                "violence",
                "gangster",
                "corruption",
                "prison",
                "gore",
            }
        ),
    ),  # fmt: skip
    "feel-good": MoodRule(
        {COMEDY: 0.5, FAMILY: 0.5, MUSIC: 0.5, ROMANCE: 0.3, ANIMATION: 0.3},
        frozenset(
            {
                "underdog",
                "sports",
                "dance",
                "musical",
                "coming of age",
                "inspirational",
                "road trip",
                "wedding",
                "friendship",
            }
        ),
    ),  # fmt: skip
}

# A matching keyword lifts the mood to at least this strength, and adds this much on top of a
# genre hint (capped at 1).
KEYWORD_FLOOR = 0.7
KEYWORD_BOOST = 0.4

GENRE_INDEX: dict[int, int] = {g: i for i, g in enumerate(GENRE_IDS)}
MOOD_INDEX: dict[str, int] = {m: len(GENRE_IDS) + i for i, m in enumerate(MOOD_TAGS)}


def mood_strengths(genre_ids: tuple[int, ...] | list[int], keywords: frozenset[str]) -> np.ndarray:
    """0-1 strength per mood, in MOOD_TAGS order."""
    out = np.zeros(len(MOOD_TAGS))
    for i, mood in enumerate(MOOD_TAGS):
        rule = MOOD_RULES[mood]
        hint = max((rule.genres.get(g, 0.0) for g in genre_ids), default=0.0)
        if keywords and not rule.keywords.isdisjoint(keywords):
            hint = max(KEYWORD_FLOOR, min(1.0, hint + KEYWORD_BOOST))
        out[i] = hint
    return out


def feature_vector(genre_ids: tuple[int, ...] | list[int], keywords: frozenset[str]) -> np.ndarray:
    """INTERPRETABLE_DIM values: genre one-hot, then mood strengths."""
    vec = np.zeros(INTERPRETABLE_DIM)
    for g in genre_ids:
        idx = GENRE_INDEX.get(g)
        if idx is not None:
            vec[idx] = 1.0
    vec[len(GENRE_IDS) :] = mood_strengths(genre_ids, keywords)
    return vec


def dim_name(index: int) -> str:
    """Readable name of an interpretable dim, as used in explanations."""
    if index < len(GENRE_IDS):
        return GENRE_NAMES.get(GENRE_IDS[index], "this genre")
    return MOOD_PHRASES[MOOD_TAGS[index - len(GENRE_IDS)]]


def is_genre_dim(index: int) -> bool:
    return index < len(GENRE_IDS)

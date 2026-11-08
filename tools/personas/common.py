"""Shared constants and helpers for the persona tooling (stdlib only).

Nothing here is a recommender. These are vocabulary tables (copied from core-api / the ML
contract), file locations and small formatting helpers used by generate.py, networks.py,
seed.py, export_csv.py and sessions.py.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
OUT_DIR = HERE / "out"

DEFAULT_PERSONAS = HERE / "personas.resolved.json"
SAMPLE_PERSONAS = HERE / "tests" / "fixtures" / "personas.resolved.sample.json"
DEFAULT_NETWORKS = HERE / "networks.json"
DEFAULT_GENERATED = OUT_DIR / "ratings.generated.json"
DEFAULT_SEED_STATE = OUT_DIR / "seed-state.json"
DEFAULT_MOVIELENS = REPO / "python-ml-service" / "eval" / "data" / "ml-latest-small"

DEFAULT_SUPABASE = "http://127.0.0.1:55321"
DEFAULT_API = "http://127.0.0.1:8090/api/v1"

# TMDB genre ids in the ML contract's GENRE_IDS order (python-ml-service/app/services/
# recommender.py, core-api reco.model.TasteDimensions), with TMDB's English names.
GENRE_NAMES: dict[int, str] = {
    28: "Action",
    12: "Adventure",
    16: "Animation",
    35: "Comedy",
    80: "Crime",
    99: "Documentary",
    18: "Drama",
    10751: "Family",
    14: "Fantasy",
    36: "History",
    27: "Horror",
    10402: "Music",
    9648: "Mystery",
    10749: "Romance",
    878: "Science Fiction",
    10770: "TV Movie",
    53: "Thriller",
    10752: "War",
    37: "Western",
}
GENRE_IDS: list[int] = list(GENRE_NAMES)

MOOD_TAGS: list[str] = [
    "mind-bending",
    "cozy",
    "laugh-out-loud",
    "edge-of-seat",
    "tearjerker",
    "visually-stunning",
    "dark-gritty",
    "feel-good",
]

# core-api enums, by their JSON (constant) names, least to most mature.
CERTIFICATIONS: list[str] = ["G", "PG", "PG_13", "R", "NC_17"]
CERT_LABELS: dict[str, str] = {"G": "G", "PG": "PG", "PG_13": "PG-13", "R": "R", "NC_17": "NC-17"}
CONTENT_DIMENSIONS: list[str] = ["VIOLENCE", "SEXUAL", "LANGUAGE", "DRUGS", "FRIGHTENING"]
CONTENT_LEVELS: list[str] = ["NONE", "MILD", "MODERATE", "STRONG"]

VOLUMES: dict[str, int] = {"none": 0, "light": 10, "medium": 40, "heavy": 120, "power": 300}
NEUTRAL = 5.0


class PersonaFileError(Exception):
    """A persona / networks / generated file is missing or malformed."""


def load_json(path: Path | str, what: str) -> Any:
    path = Path(path)
    if not path.exists():
        raise PersonaFileError(f"{what} not found: {path}")
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except json.JSONDecodeError as e:
        raise PersonaFileError(f"{what} is not valid JSON ({path}): {e}") from e


def load_personas(path: Path | str | None = None) -> dict:
    """The resolved persona file (personas.resolved.json: favourites/anchors carry `tmdbId`)."""
    path = Path(path) if path else DEFAULT_PERSONAS
    if not path.exists():
        raise PersonaFileError(
            f"{path} not found. Run `python3 -I tools/personas/resolve.py` first "
            f"(or pass --personas {SAMPLE_PERSONAS.relative_to(REPO)} to try the sample)."
        )
    data = load_json(path, "persona file")
    if not isinstance(data, dict) or not isinstance(data.get("personas"), list):
        raise PersonaFileError(f"{path}: expected an object with a `personas` list")
    return data


def personas_by_key(data: dict) -> dict[str, dict]:
    return {p["key"]: p for p in data["personas"]}


def select(data: dict, keys: list[str] | None) -> list[dict]:
    """Personas in file order, optionally only `keys` (unknown keys are an error)."""
    if not keys:
        return list(data["personas"])
    by_key = personas_by_key(data)
    unknown = [k for k in keys if k not in by_key]
    if unknown:
        raise PersonaFileError(f"unknown persona key(s): {', '.join(unknown)}")
    wanted = set(keys)
    return [p for p in data["personas"] if p["key"] in wanted]


def parse_keys(raw: str | None) -> list[str] | None:
    if not raw:
        return None
    return [k.strip() for k in raw.split(",") if k.strip()]


def email_for(data: dict, persona: dict) -> str:
    return f"{persona['key']}@{data.get('emailDomain', 'filmfuse.test')}"


def movie_id(entry: dict) -> int | None:
    """tmdbId of a resolved favourite/anchor (None when unresolved)."""
    value = entry.get("tmdbId")
    return int(value) if value is not None else None


def reserved_ids(persona: dict) -> set[int]:
    """Favourite + anchor TMDB ids: generated ratings must never include these."""
    out: set[int] = set()
    for entry in list(persona.get("favorites", [])) + list(persona.get("anchorRatings", [])):
        mid = movie_id(entry)
        if mid is not None:
            out.add(mid)
    return out


def title_year(entry: dict) -> str:
    year = entry.get("year")
    return f"{entry['title']} ({year})" if year else str(entry["title"])


def genre_name(genre_id: int | str) -> str:
    return GENRE_NAMES.get(int(genre_id), f"Genre {genre_id}")


def stated_affinity(genres: dict, genre_ids: list[int] | tuple[int, ...]) -> float:
    """Mean of the persona's stated genre scores for these genres (unknown genre = neutral 5).

    A movie without genres scores neutral 5. Used as an OUTCOME diagnostic (sessions.py) and
    for synthetic data generation (generate.py) - it is not a recommender.
    """
    if not genre_ids:
        return NEUTRAL
    scores = [float(genres.get(str(g), genres.get(g, NEUTRAL))) for g in genre_ids]
    return sum(scores) / len(scores)


def normalise_comfort(comfort: dict | None) -> dict:
    """Persona comfort -> the exact PUT /me/settings/comfort JSON (core-api enum names).

    Accepts "PG-13"/"pg_13" style spellings; raises ValueError for anything core-api would refuse.
    None/omitted = no limits.
    """
    if not comfort:
        return {"maxCertification": None, "contentLimits": {}, "allowUnrated": False}
    cert = comfort.get("maxCertification")
    if cert is not None:
        cert = str(cert).strip().upper().replace("-", "_")
        if cert not in CERTIFICATIONS:
            raise ValueError(f"unknown maxCertification {comfort.get('maxCertification')!r}")
    limits: dict[str, str] = {}
    for dim, level in (comfort.get("contentLimits") or {}).items():
        d = str(dim).strip().upper()
        lv = str(level).strip().upper()
        if d not in CONTENT_DIMENSIONS:
            raise ValueError(f"unknown content dimension {dim!r}")
        if lv not in CONTENT_LEVELS:
            raise ValueError(f"unknown content level {level!r} for {dim}")
        limits[d] = lv
    return {
        "maxCertification": cert,
        "contentLimits": dict(sorted(limits.items())),
        "allowUnrated": bool(comfort.get("allowUnrated", False)),
    }


def comfort_summary(comfort: dict | None) -> str:
    c = normalise_comfort(comfort)
    parts: list[str] = []
    if c["maxCertification"]:
        parts.append(f"max {CERT_LABELS[c['maxCertification']]}")
    for dim, level in c["contentLimits"].items():
        if level != "STRONG":
            parts.append(f"{dim.lower()} <= {level.lower()}")
    if not parts:
        return "none"
    parts.append("unrated allowed" if c["allowUnrated"] else "unrated blocked")
    return "; ".join(parts)


def rater_style(rater: dict | None) -> str:
    rater = rater or {}
    bias = float(rater.get("bias", 0.0))
    spread = float(rater.get("spread", 1.0))
    parts = []
    if bias >= 1.0:
        parts.append("generous")
    elif bias <= -1.0:
        parts.append("harsh")
    if spread <= 0.6:
        parts.append("flat")
    elif spread >= 1.5:
        parts.append("volatile")
    return ", ".join(parts) or "normal"


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")
    tmp.replace(path)

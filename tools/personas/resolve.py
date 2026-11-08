#!/usr/bin/env python3
"""Validate ``personas.json`` and resolve every favourite/anchor title to a TMDB id.

Stdlib only. Run from the repo root:

    python3 -I tools/personas/resolve.py [--movielens DIR]

Resolution order for each ``{title, year}``:
  1. ``tmdb-overrides.json`` (``{"<Title> (<year>)": tmdbId}``), checked first so it can
     also correct a bad MovieLens match;
  2. MovieLens ``ml-latest-small`` (``movies.csv`` + ``links.csv``), matched on a normalised
     title and the release year (exact year, or +/-1 only when exactly one candidate fits).

Writes ``personas.resolved.json`` (sorted keys, 2-space indent) on success. On any problem it
prints every error and exits non-zero without writing.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parent.parent
DEFAULT_INPUT = HERE / "personas.json"
DEFAULT_OVERRIDES = HERE / "tmdb-overrides.json"
DEFAULT_OUTPUT = HERE / "personas.resolved.json"
DEFAULT_MOVIELENS = REPO_ROOT / "python-ml-service" / "eval" / "data" / "ml-latest-small"

GENRE_IDS: dict[str, str] = {
    "28": "Action",
    "12": "Adventure",
    "16": "Animation",
    "35": "Comedy",
    "80": "Crime",
    "99": "Documentary",
    "18": "Drama",
    "10751": "Family",
    "14": "Fantasy",
    "36": "History",
    "27": "Horror",
    "10402": "Music",
    "9648": "Mystery",
    "10749": "Romance",
    "878": "Science Fiction",
    "10770": "TV Movie",
    "53": "Thriller",
    "10752": "War",
    "37": "Western",
}
MOOD_TAGS = frozenset(
    {
        "mind-bending",
        "cozy",
        "laugh-out-loud",
        "edge-of-seat",
        "tearjerker",
        "visually-stunning",
        "dark-gritty",
        "feel-good",
    }
)
CERTIFICATIONS = frozenset({"G", "PG", "PG_13", "R", "NC_17"})
CONTENT_KEYS = frozenset({"VIOLENCE", "SEXUAL", "LANGUAGE", "DRUGS", "FRIGHTENING"})
CONTENT_LEVELS = frozenset({"NONE", "MILD", "MODERATE", "STRONG"})
# Strictest selectable limit per dimension (core-api ContentDimension.lowestLimit): violence and
# frightening start at MILD, because the content tagger never rates a film "none" for them.
MILD_FLOOR_KEYS = frozenset({"VIOLENCE", "FRIGHTENING"})
VOLUMES = frozenset({"none", "light", "medium", "heavy", "power"})
POPULARITY = frozenset({"mainstream", "mixed", "niche"})

KEY_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
USERNAME_RE = re.compile(r"^[a-z0-9_]{3,30}$")

ONBOARDING_MIN_GENRES = 5
ONBOARDING_MIN_LOVED = 3
ONBOARDING_LOVED_SCORE = 8
FAVORITES_MIN, FAVORITES_MAX = 3, 10
ANCHORS_MAX = 40

# Leading articles stripped during normalisation (English plus the common ones MovieLens
# moves to the end of foreign titles, e.g. "Fabuleux destin d'Amélie Poulain, Le").
ARTICLES = frozenset(
    {
        "the",
        "a",
        "an",
        "le",
        "la",
        "les",
        "l",
        "el",
        "los",
        "las",
        "il",
        "lo",
        "der",
        "die",
        "das",
    }
)
_TRAILING_ARTICLE_RE = re.compile(r"^(?P<body>.*),\s*(?P<art>[\w']+)$", re.UNICODE)
_YEAR_SUFFIX_RE = re.compile(r"\s*\((?P<year>\d{4})(?:[-–]\d{0,4})?\)\s*$")


class ResolveError(Exception):
    """Raised for an unresolvable or ambiguous title."""


# --------------------------------------------------------------------------- normalisation


def strip_accents(text: str) -> str:
    decomposed = unicodedata.normalize("NFKD", text)
    return "".join(ch for ch in decomposed if not unicodedata.combining(ch))


def _unmove_article(title: str) -> str:
    """'Godfather, The' -> 'The Godfather' (only when the tail really is an article)."""
    m = _TRAILING_ARTICLE_RE.match(title.strip())
    if m and m.group("art").lower().rstrip("'") in ARTICLES:
        art = m.group("art")
        sep = "" if art.endswith("'") else " "
        return f"{art}{sep}{m.group('body').strip()}"
    return title


def normalize_title(title: str) -> str:
    """Case/accent/punctuation-insensitive key with leading articles removed."""
    text = strip_accents(_unmove_article(title)).lower()
    text = text.replace("&", " and ")
    text = re.sub(r"[^\w\s]", " ", text)  # punctuation -> space ("l'" -> "l ")
    words = text.split()
    while len(words) > 1 and words[0] in ARTICLES:
        words = words[1:]
    return " ".join(words)


def split_movielens_title(raw: str) -> tuple[list[str], int | None]:
    """Split 'Amélie (Fabuleux destin d'Amélie Poulain, Le) (2001)' into
    (['Amélie', "Fabuleux destin d'Amélie Poulain, Le"], 2001)."""
    raw = raw.strip()
    year: int | None = None
    m = _YEAR_SUFFIX_RE.search(raw)
    if m:
        year = int(m.group("year"))
        raw = raw[: m.start()].strip()
    names: list[str] = []
    # Peel trailing "(alt title)" groups off the end; anything left is the primary title.
    while raw.endswith(")"):
        depth = 0
        start = None
        for i in range(len(raw) - 1, -1, -1):
            if raw[i] == ")":
                depth += 1
            elif raw[i] == "(":
                depth -= 1
                if depth == 0:
                    start = i
                    break
        if start is None or start == 0:
            break
        alt = raw[start + 1 : -1].strip()
        if alt.lower().startswith("a.k.a. "):
            alt = alt[7:]
        names.append(alt)
        raw = raw[:start].strip()
    names.insert(0, raw)
    return [n for n in names if n], year


def title_keys(raw: str) -> tuple[set[str], int | None]:
    names, year = split_movielens_title(raw)
    keys = {normalize_title(n) for n in names}
    # Also index the full un-split title, for films whose real name contains parentheses.
    full = _YEAR_SUFFIX_RE.sub("", raw).strip()
    keys.add(normalize_title(full))
    keys.discard("")
    return keys, year


# --------------------------------------------------------------------------- index


@dataclass
class Movie:
    movie_id: int
    raw_title: str
    year: int | None
    tmdb_id: int | None


@dataclass
class MovieIndex:
    by_key: dict[str, list[Movie]] = field(default_factory=dict)

    def add(self, movie: Movie, keys: set[str]) -> None:
        for key in keys:
            self.by_key.setdefault(key, []).append(movie)

    @classmethod
    def from_rows(cls, movies: list[tuple[int, str]], links: dict[int, int | None]) -> MovieIndex:
        index = cls()
        for movie_id, raw in movies:
            keys, year = title_keys(raw)
            index.add(Movie(movie_id, raw, year, links.get(movie_id)), keys)
        return index

    @classmethod
    def load(cls, directory: Path) -> MovieIndex:
        movies_csv = directory / "movies.csv"
        links_csv = directory / "links.csv"
        if not movies_csv.is_file() or not links_csv.is_file():
            raise FileNotFoundError(f"MovieLens files not found in {directory}")
        links: dict[int, int | None] = {}
        with links_csv.open(newline="", encoding="utf-8") as fh:
            for row in csv.DictReader(fh):
                tmdb = (row.get("tmdbId") or "").strip()
                links[int(row["movieId"])] = int(tmdb) if tmdb else None
        movies: list[tuple[int, str]] = []
        with movies_csv.open(newline="", encoding="utf-8") as fh:
            for row in csv.DictReader(fh):
                movies.append((int(row["movieId"]), row["title"]))
        return cls.from_rows(movies, links)

    def match(self, title: str, year: int) -> Movie:
        key = normalize_title(title)
        candidates = {m.movie_id: m for m in self.by_key.get(key, [])}.values()
        exact = [m for m in candidates if m.year == year]
        if len(exact) == 1:
            return exact[0]
        if len(exact) > 1:
            names = ", ".join(sorted(m.raw_title for m in exact))
            raise ResolveError(f"ambiguous: {title} ({year}) matches {names}")
        near = [m for m in candidates if m.year is not None and abs(m.year - year) == 1]
        if len(near) == 1:
            return near[0]
        if len(near) > 1:
            names = ", ".join(sorted(m.raw_title for m in near))
            raise ResolveError(f"ambiguous: {title} ({year}) matches {names}")
        raise ResolveError(f"not found in MovieLens: {title} ({year})")


def override_key(title: str, year: int) -> str:
    return f"{title} ({year})"


def resolve_title(
    title: str, year: int, index: MovieIndex | None, overrides: dict[str, int]
) -> tuple[int, str]:
    """Return (tmdbId, source) where source is 'overrides' or 'movielens'."""
    key = override_key(title, year)
    if key in overrides:
        return overrides[key], "overrides"
    if index is None:
        raise ResolveError(f"not in overrides and no MovieLens index: {key}")
    movie = index.match(title, year)
    if movie.tmdb_id is None:
        raise ResolveError(f"MovieLens has no TMDB link for {movie.raw_title}")
    return movie.tmdb_id, "movielens"


# --------------------------------------------------------------------------- validation


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _is_num(value: Any) -> bool:
    return isinstance(value, int | float) and not isinstance(value, bool)


def _check_movie_ref(ref: Any, where: str, errors: list[str], *, scored: bool) -> bool:
    if not isinstance(ref, dict):
        errors.append(f"{where}: must be an object with title and year")
        return False
    ok = True
    if not isinstance(ref.get("title"), str) or not ref["title"].strip():
        errors.append(f"{where}: title must be a non-empty string")
        ok = False
    if not _is_int(ref.get("year")) or not 1880 <= ref["year"] <= 2030:
        errors.append(f"{where}: year must be an integer 1880-2030")
        ok = False
    if scored and (not _is_int(ref.get("score")) or not 1 <= ref["score"] <= 10):
        errors.append(f"{where}: score out of 1-10: {ref.get('score')!r}")
    if "tmdbId" in ref and (not _is_int(ref["tmdbId"]) or ref["tmdbId"] <= 0):
        errors.append(f"{where}: tmdbId must be a positive integer")
    return ok


def validate_persona(p: Any, idx: int) -> list[str]:
    """Schema + onboarding checks for one persona (no title resolution)."""
    errors: list[str] = []
    if not isinstance(p, dict):
        return [f"personas[{idx}]: must be an object"]
    label = f"persona {p.get('key', f'#{idx}')!s}"

    key = p.get("key")
    if not isinstance(key, str) or not KEY_RE.match(key):
        errors.append(f"{label}: key must be kebab-case [a-z0-9-]: {key!r}")
    username = p.get("username")
    if not isinstance(username, str) or not USERNAME_RE.match(username):
        errors.append(f"{label}: username must be 3-30 chars of [a-z0-9_]: {username!r}")
    for text_field in ("displayName", "archetype", "bio"):
        if not isinstance(p.get(text_field), str) or not p[text_field].strip():
            errors.append(f"{label}: {text_field} must be a non-empty string")
    tags = p.get("tags", [])
    if not isinstance(tags, list) or not all(isinstance(t, str) for t in tags):
        errors.append(f"{label}: tags must be a list of strings")

    genres = p.get("genres")
    if not isinstance(genres, dict):
        errors.append(f"{label}: genres must be an object")
        genres = {}
    for gid, score in genres.items():
        if gid not in GENRE_IDS:
            errors.append(f"{label}: unknown genre id {gid!r}")
        if not _is_int(score) or not 1 <= score <= 10:
            errors.append(f"{label}: genre {gid} score out of 1-10: {score!r}")
    loved = [s for s in genres.values() if _is_int(s) and s >= ONBOARDING_LOVED_SCORE]
    if len(genres) < ONBOARDING_MIN_GENRES or len(loved) < ONBOARDING_MIN_LOVED:
        errors.append(
            f"{label}: onboarding rule needs >= {ONBOARDING_MIN_GENRES} genre scores with "
            f">= {ONBOARDING_MIN_LOVED} of them >= {ONBOARDING_LOVED_SCORE} "
            f"(has {len(genres)} scores, {len(loved)} >= {ONBOARDING_LOVED_SCORE})"
        )

    moods = p.get("moods", {})
    if not isinstance(moods, dict):
        errors.append(f"{label}: moods must be an object")
        moods = {}
    for mood, score in moods.items():
        if mood not in MOOD_TAGS:
            errors.append(f"{label}: unknown mood {mood!r}")
        if not _is_int(score) or not 1 <= score <= 10:
            errors.append(f"{label}: mood {mood} score out of 1-10: {score!r}")

    favorites = p.get("favorites")
    if not isinstance(favorites, list):
        errors.append(f"{label}: favorites must be a list")
        favorites = []
    if not FAVORITES_MIN <= len(favorites) <= FAVORITES_MAX:
        errors.append(
            f"{label}: favorites must have {FAVORITES_MIN}-{FAVORITES_MAX} entries "
            f"(has {len(favorites)})"
        )
    anchors = p.get("anchorRatings", [])
    if not isinstance(anchors, list):
        errors.append(f"{label}: anchorRatings must be a list")
        anchors = []
    if len(anchors) > ANCHORS_MAX:
        errors.append(f"{label}: anchorRatings has {len(anchors)} entries (max {ANCHORS_MAX})")

    seen: dict[tuple[str, int], str] = {}
    for kind, refs, scored in (
        ("favorites", favorites, False),
        ("anchorRatings", anchors, True),
    ):
        for i, ref in enumerate(refs):
            where = f"{label}: {kind}[{i}]"
            if not _check_movie_ref(ref, where, errors, scored=scored):
                continue
            ident = (normalize_title(ref["title"]), ref["year"])
            if ident in seen:
                errors.append(
                    f"{label}: same movie twice: {ref['title']} ({ref['year']}) "
                    f"in {seen[ident]} and {kind}"
                )
            else:
                seen[ident] = kind

    if p.get("volume") not in VOLUMES:
        errors.append(f"{label}: volume must be one of {sorted(VOLUMES)}: {p.get('volume')!r}")

    rater = p.get("rater")
    if not isinstance(rater, dict):
        errors.append(f"{label}: rater must be an object with bias and spread")
    else:
        bias, spread = rater.get("bias"), rater.get("spread")
        if not _is_num(bias) or not -3 <= bias <= 3:
            errors.append(f"{label}: rater.bias must be a number -3..3: {bias!r}")
        if not _is_num(spread) or not 0.3 <= spread <= 2.0:
            errors.append(f"{label}: rater.spread must be a number 0.3..2.0: {spread!r}")

    if "era" in p:
        era = p["era"]
        if (
            not isinstance(era, dict)
            or not _is_int(era.get("from"))
            or not _is_int(era.get("to"))
            or era["from"] > era["to"]
        ):
            errors.append(f"{label}: era must be {{from, to}} integers with from <= to")

    if p.get("popularity") not in POPULARITY:
        errors.append(
            f"{label}: popularity must be one of {sorted(POPULARITY)}: {p.get('popularity')!r}"
        )

    if "comfort" in p:
        errors.extend(_validate_comfort(p["comfort"], label))

    if not isinstance(p.get("onboarded"), bool):
        errors.append(f"{label}: onboarded must be true or false")
    return errors


def _validate_comfort(comfort: Any, label: str) -> list[str]:
    errors: list[str] = []
    if not isinstance(comfort, dict):
        return [f"{label}: comfort must be an object"]
    cert = comfort.get("maxCertification")
    if cert is not None and cert not in CERTIFICATIONS:
        errors.append(f"{label}: comfort.maxCertification invalid: {cert!r}")
    limits = comfort.get("contentLimits", {})
    if not isinstance(limits, dict):
        errors.append(f"{label}: comfort.contentLimits must be an object")
        limits = {}
    for k, v in limits.items():
        if k not in CONTENT_KEYS:
            errors.append(f"{label}: comfort.contentLimits key invalid: {k!r}")
        if v not in CONTENT_LEVELS:
            errors.append(f"{label}: comfort.contentLimits[{k}] value invalid: {v!r}")
        elif k in MILD_FLOOR_KEYS and v == "NONE":
            errors.append(f"{label}: comfort.contentLimits[{k}] cannot be NONE (lowest is MILD)")
    if "allowUnrated" in comfort and not isinstance(comfort["allowUnrated"], bool):
        errors.append(f"{label}: comfort.allowUnrated must be true or false")
    return errors


def validate_document(doc: Any) -> list[str]:
    """Validate the whole personas document (schema, onboarding, uniqueness)."""
    if not isinstance(doc, dict):
        return ["document must be a JSON object"]
    errors: list[str] = []
    if doc.get("version") != 1:
        errors.append(f"version must be 1: {doc.get('version')!r}")
    if not isinstance(doc.get("password"), str) or len(doc["password"]) < 8:
        errors.append("password must be a string of at least 8 characters")
    domain = doc.get("emailDomain")
    if not isinstance(domain, str) or "." not in domain or "@" in domain:
        errors.append(f"emailDomain must be a bare domain: {domain!r}")
        domain = ""
    personas = doc.get("personas")
    if not isinstance(personas, list) or not personas:
        return [*errors, "personas must be a non-empty list"]

    seen_keys: set[str] = set()
    seen_usernames: set[str] = set()
    seen_emails: set[str] = set()
    for i, p in enumerate(personas):
        errors.extend(validate_persona(p, i))
        if not isinstance(p, dict):
            continue
        key, username = p.get("key"), p.get("username")
        if isinstance(key, str):
            if key in seen_keys:
                errors.append(f"duplicate key: {key}")
            seen_keys.add(key)
            email = f"{key}@{domain}".lower()
            if email in seen_emails:
                errors.append(f"duplicate email: {email}")
            seen_emails.add(email)
        if isinstance(username, str):
            if username.lower() in seen_usernames:
                errors.append(f"duplicate username: {username}")
            seen_usernames.add(username.lower())
    return errors


def validate_overrides(overrides: Any) -> list[str]:
    if not isinstance(overrides, dict):
        return ["tmdb-overrides.json must be an object"]
    errors = []
    for k, v in overrides.items():
        if not _YEAR_SUFFIX_RE.search(k):
            errors.append(f"override key must look like 'Title (YYYY)': {k!r}")
        if not _is_int(v) or v <= 0:
            errors.append(f"override {k!r} must map to a positive integer TMDB id")
    return errors


# --------------------------------------------------------------------------- resolution


def resolve_document(
    doc: dict, index: MovieIndex | None, overrides: dict[str, int]
) -> tuple[dict, list[str]]:
    """Return (resolved copy, errors). Errors include unresolved titles and duplicate ids."""
    out = json.loads(json.dumps(doc))
    errors: list[str] = []
    counts = {"movielens": 0, "overrides": 0}
    for p in out.get("personas", []):
        label = f"persona {p.get('key')}"
        ids_seen: dict[int, str] = {}
        for kind in ("favorites", "anchorRatings"):
            for ref in p.get(kind, []):
                if not isinstance(ref, dict) or "title" not in ref or "year" not in ref:
                    continue
                try:
                    tmdb_id, source = resolve_title(ref["title"], ref["year"], index, overrides)
                except ResolveError as exc:
                    errors.append(f"{label}: {kind}: {exc}")
                    continue
                ref["tmdbId"] = tmdb_id
                counts[source] += 1
                if tmdb_id in ids_seen:
                    errors.append(
                        f"{label}: same movie twice (tmdbId {tmdb_id}): "
                        f"{ids_seen[tmdb_id]} and {ref['title']} ({ref['year']})"
                    )
                else:
                    ids_seen[tmdb_id] = f"{ref['title']} ({ref['year']})"
    out["resolvedFrom"] = counts
    return out, errors


def dump_json(data: Any) -> str:
    return json.dumps(data, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def run(
    input_path: Path = DEFAULT_INPUT,
    overrides_path: Path = DEFAULT_OVERRIDES,
    movielens_dir: Path = DEFAULT_MOVIELENS,
    output_path: Path | None = DEFAULT_OUTPUT,
) -> tuple[dict | None, list[str]]:
    """Validate + resolve; write output only when there are no errors."""
    doc = json.loads(input_path.read_text(encoding="utf-8"))
    overrides = (
        json.loads(overrides_path.read_text(encoding="utf-8")) if overrides_path.is_file() else {}
    )
    errors = validate_document(doc) + validate_overrides(overrides)
    try:
        index: MovieIndex | None = MovieIndex.load(movielens_dir)
    except FileNotFoundError as exc:
        errors.append(str(exc))
        index = None
    if isinstance(doc, dict) and isinstance(overrides, dict):
        resolved, res_errors = resolve_document(doc, index, overrides)
        errors.extend(res_errors)
    else:
        resolved = None
    if errors:
        return None, errors
    if output_path is not None:
        output_path.write_text(dump_json(resolved), encoding="utf-8")
    return resolved, []


def load_resolved(path: str | Path | None = None) -> dict:
    """Load ``personas.resolved.json`` and check it is valid and fully resolved.

    Raises ValueError listing every problem.
    """
    resolved_path = Path(path) if path is not None else DEFAULT_OUTPUT
    doc = json.loads(resolved_path.read_text(encoding="utf-8"))
    errors = validate_document(doc)
    for p in doc.get("personas", []) if isinstance(doc, dict) else []:
        for kind in ("favorites", "anchorRatings"):
            for i, ref in enumerate(p.get(kind, [])):
                if not isinstance(ref, dict) or not _is_int(ref.get("tmdbId")):
                    errors.append(f"persona {p.get('key')}: {kind}[{i}] has no tmdbId")
    if errors:
        raise ValueError(f"{resolved_path} is invalid:\n  " + "\n  ".join(errors))
    return doc


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--movielens", type=Path, default=DEFAULT_MOVIELENS, metavar="DIR")
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--overrides", type=Path, default=DEFAULT_OVERRIDES)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true", help="validate only, write nothing")
    args = parser.parse_args(argv)

    resolved, errors = run(
        args.input, args.overrides, args.movielens, None if args.check else args.output
    )
    if errors:
        print(f"{len(errors)} problem(s):", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1
    assert resolved is not None
    counts = resolved["resolvedFrom"]
    target = "(check only)" if args.check else str(args.output)
    print(
        f"OK: {len(resolved['personas'])} personas, "
        f"{counts['movielens']} titles via MovieLens, {counts['overrides']} via overrides "
        f"-> {target}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

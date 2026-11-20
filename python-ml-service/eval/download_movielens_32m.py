"""Download MovieLens ml-32m (32M ratings, ~200k users) into eval/data/ml-32m/ (gitignored).

    python -m eval.download_movielens_32m [--sha256 <hex>] [--force]

Evaluation only: GroupLens licenses MovieLens for research; no commercial use of the data in the
product without their permission. It is far too big to evaluate whole, so draw user samples
with `python -m eval.subsample` and point the harness at a sample folder.

Safety, as eval.download_movielens: the zip is untrusted data, streamed to a temporary file
(size-capped), and only the three expected CSVs are copied out by exact member name to fixed
file names; nothing is executed or otherwise extracted. The zip's SHA-256 is printed.
"""

from __future__ import annotations

import argparse
import hashlib
import shutil
import sys
import tempfile
import urllib.request
import zipfile
from pathlib import Path

URL = "https://files.grouplens.org/datasets/movielens/ml-32m.zip"
# SHA-256 of ml-32m.zip as first downloaded on 2026-11-19 (trust-on-first-use pin).
EXPECTED_SHA256: str | None = "e4a68655d7386b8f95f2f2424b2ff975dfdd15ffd59e0d864a14dca43e99d6ee"
TARGET_DIR = Path(__file__).resolve().parent / "data" / "ml-32m"
MEMBERS = ("ratings.csv", "movies.csv", "links.csv")
MAX_ZIP_BYTES = 400 * 1024 * 1024
MAX_MEMBER_BYTES = 1200 * 1024 * 1024
CHUNK = 1 << 20


def fetch_to(path: Path, url: str = URL) -> str:
    digest = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(url, timeout=120) as resp, path.open("wb") as out:  # noqa: S310
        while chunk := resp.read(CHUNK):
            total += len(chunk)
            if total > MAX_ZIP_BYTES:
                raise RuntimeError("download larger than expected; refusing")
            digest.update(chunk)
            out.write(chunk)
    print(f"downloaded {total} bytes")
    return digest.hexdigest()


def extract(zip_path: Path, target_dir: Path = TARGET_DIR) -> list[Path]:
    target_dir.mkdir(parents=True, exist_ok=True)
    written = []
    with zipfile.ZipFile(zip_path) as zf:
        for name in MEMBERS:
            info = zf.getinfo(f"ml-32m/{name}")  # KeyError if absent
            if info.file_size > MAX_MEMBER_BYTES:
                raise RuntimeError(f"{name} is unexpectedly large ({info.file_size} bytes)")
            out = target_dir / name  # fixed name; member path never used for the destination
            with zf.open(info) as src, out.open("wb") as dst:
                shutil.copyfileobj(src, dst, CHUNK)
            written.append(out)
    return written


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    p.add_argument("--sha256", default=EXPECTED_SHA256, help="expected SHA-256 of the zip")
    p.add_argument("--force", action="store_true", help="re-download even if present")
    args = p.parse_args(argv)
    if not args.force and all((TARGET_DIR / m).exists() for m in MEMBERS):
        print(f"Already present in {TARGET_DIR} (use --force to re-download).")
        return 0
    with tempfile.TemporaryDirectory() as tmp:
        zip_path = Path(tmp) / "ml-32m.zip"
        print(f"Downloading {URL} ...")
        digest = fetch_to(zip_path)
        print(f"SHA-256: {digest}")
        if args.sha256 and digest != args.sha256.lower():
            print(f"SHA-256 mismatch: expected {args.sha256}", file=sys.stderr)
            return 1
        for path in extract(zip_path):
            print(f"  wrote {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

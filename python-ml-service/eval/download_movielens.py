"""Download MovieLens ml-latest-small into eval/data/ (gitignored).

    python -m eval.download_movielens [--sha256 <hex>] [--force]

Safety: the zip is treated as untrusted data. Only the four expected CSVs are read out of
it (by exact member name, size-capped) and written to fixed file names; nothing in it is
executed and no other member is extracted. The zip's SHA-256 is always printed. The
GroupLens zip is periodically regenerated, so no hash is pinned by default; pass
`--sha256` (or set EXPECTED_SHA256) to pin the one you have verified.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import sys
import urllib.request
import zipfile
from pathlib import Path

URL = "https://files.grouplens.org/datasets/movielens/ml-latest-small.zip"
# SHA-256 of ml-latest-small.zip as first downloaded on 2026-10-16 (trust-on-first-use pin).
EXPECTED_SHA256: str | None = "696d65a3dfceac7c45750ad32df2c259311949efec81f0f144fdfb91ebc9e436"
DATA_ROOT = Path(__file__).resolve().parent / "data"
TARGET_DIR = DATA_ROOT / "ml-latest-small"
MEMBERS = ("ratings.csv", "movies.csv", "links.csv", "tags.csv")
MAX_ZIP_BYTES = 50 * 1024 * 1024
MAX_MEMBER_BYTES = 50 * 1024 * 1024


def fetch(url: str = URL) -> bytes:
    with urllib.request.urlopen(url, timeout=60) as resp:  # noqa: S310 (fixed https URL)
        data = resp.read(MAX_ZIP_BYTES + 1)
    if len(data) > MAX_ZIP_BYTES:
        raise RuntimeError("download larger than expected; refusing")
    return data


def extract(zip_bytes: bytes, target_dir: Path = TARGET_DIR) -> list[Path]:
    """Write only the expected CSVs (looked up as `ml-latest-small/<name>`) to target_dir."""
    target_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as zf:
        for name in MEMBERS:
            info = zf.getinfo(f"ml-latest-small/{name}")  # KeyError if absent
            if info.file_size > MAX_MEMBER_BYTES:
                raise RuntimeError(f"{name} is unexpectedly large ({info.file_size} bytes)")
            out = target_dir / name  # fixed name; member path never used for the destination
            out.write_bytes(zf.read(info))
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
    print(f"Downloading {URL} ...")
    data = fetch()
    digest = hashlib.sha256(data).hexdigest()
    print(f"SHA-256: {digest}  ({len(data)} bytes)")
    if args.sha256 and digest != args.sha256.lower():
        print(f"SHA-256 mismatch: expected {args.sha256}", file=sys.stderr)
        return 1
    if not args.sha256:
        print("No expected hash pinned; verify the digest above if you need to.")
    for path in extract(data):
        print(f"  wrote {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

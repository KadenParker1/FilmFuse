import io
import zipfile
from pathlib import Path

import pytest

from eval.download_movielens import MEMBERS, extract


def _zip(members: dict[str, str]) -> bytes:
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, text in members.items():
            zf.writestr(name, text)
    return buf.getvalue()


def test_extracts_only_expected_csvs(tmp_path: Path) -> None:
    members = {f"ml-latest-small/{n}": f"{n}\n" for n in MEMBERS}
    members["ml-latest-small/README.txt"] = "readme"
    members["../evil.py"] = "raise SystemExit"
    out = tmp_path / "out"
    written = extract(_zip(members), out)
    assert sorted(p.name for p in written) == sorted(MEMBERS)
    assert sorted(p.name for p in out.iterdir()) == sorted(MEMBERS)
    assert not (tmp_path / "evil.py").exists()
    assert (out / "ratings.csv").read_text() == "ratings.csv\n"


def test_missing_member_raises(tmp_path: Path) -> None:
    with pytest.raises(KeyError):
        extract(_zip({"ml-latest-small/ratings.csv": "x"}), tmp_path)

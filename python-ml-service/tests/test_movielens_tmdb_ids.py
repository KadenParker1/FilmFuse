"""tools/catalog/movielens_tmdb_ids.py - lives outside this package; tested here so CI runs it."""

import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "tools" / "catalog" / "movielens_tmdb_ids.py"
_spec = importlib.util.spec_from_file_location("movielens_tmdb_ids", SCRIPT)
tool = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tool)

LINKS = """movieId,imdbId,tmdbId
1,0114709,862
2,0113497,8844
3,0113228,
4,0114885,862
5,0113041,11862.0
6,0113277,not-a-number
7,0114319,0
8,0112302,  550
"""


def test_unique_non_empty_ids_in_file_order(tmp_path):
    links = tmp_path / "links.csv"
    links.write_text(LINKS)

    assert tool.read_links(links) == [862, 8844, 11862, 550]


def test_main_writes_an_id_file_the_seeder_can_read(tmp_path, capsys):
    links = tmp_path / "links.csv"
    links.write_text(LINKS)
    out = tmp_path / "nested" / "ids.txt"

    assert tool.main([str(links), "-o", str(out)]) == 0

    lines = out.read_text().splitlines()
    assert lines[0].startswith("# ")
    assert lines[1:] == ["862", "8844", "11862", "550"]
    assert "Wrote 4 TMDB ids" in capsys.readouterr().out


def test_missing_input_or_wrong_columns_fail_cleanly(tmp_path, capsys):
    assert tool.main([str(tmp_path / "nope.csv"), "-o", str(tmp_path / "x.txt")]) == 2
    wrong = tmp_path / "ratings.csv"
    wrong.write_text("userId,movieId,rating\n1,1,4.0\n")
    assert tool.main([str(wrong), "-o", str(tmp_path / "x.txt")]) == 2
    assert not (tmp_path / "x.txt").exists()
    with pytest.raises(ValueError):
        tool.read_links(wrong)


def test_default_output_is_gitignored():
    ignore = (SCRIPT.parent / ".gitignore").read_text().splitlines()
    assert tool.DEFAULT_OUT.parent.name + "/" in ignore

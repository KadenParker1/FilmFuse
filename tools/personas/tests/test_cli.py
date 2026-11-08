import subprocess
import sys

import pytest
from personas_testlib import PERSONAS_DIR

SCRIPTS = ["generate.py", "networks.py", "seed.py", "export_csv.py", "sessions.py"]


@pytest.mark.parametrize("script", SCRIPTS)
def test_help(script):
    # -I like the documented invocation: the scripts must find their siblings on their own.
    res = subprocess.run(
        [sys.executable, "-I", str(PERSONAS_DIR / script), "--help"],
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert res.returncode == 0, res.stderr
    assert "usage:" in res.stdout


def test_seed_dry_run_on_sample_via_cli():
    sample = PERSONAS_DIR / "tests" / "fixtures" / "personas.resolved.sample.json"
    res = subprocess.run(
        [
            sys.executable,
            "-I",
            str(PERSONAS_DIR / "seed.py"),
            "--dry-run",
            "--only",
            "personas",
            "--personas",
            str(sample),
            "--generated",
            "/nonexistent.json",
        ],
        capture_output=True,
        text=True,
        timeout=60,
        env={"PATH": "/usr/bin:/bin"},
    )
    assert res.returncode == 0, res.stderr
    assert "DRY RUN" in res.stdout and "[disney-mom]" in res.stdout

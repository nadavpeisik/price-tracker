"""Loader for browser-side JS assets evaluated via Playwright's page.evaluate().

Each *.js sibling is a bare arrow-function expression (() => {...}); main.py loads them into its
module-level _*_SCRIPT constants at import time (issue #135). The scripts stay Python-only at
runtime — the .js files exist for editor type-checking / syntax-highlighting / lint and for direct
unit-testing through the pytest+Playwright harness.
"""

from functools import cache
from pathlib import Path

_SCRIPTS_DIR = Path(__file__).resolve().parent


@cache
def load_script(name: str) -> str:
    """Read browser_scripts/<name>.js once, append a sourceURL pragma for readable Chromium stack
    traces, and cache. CWD-independent (the path is __file__-relative), so it works whether pytest
    runs from the repo root (CI) or from scraper/ (local). A missing/typo'd name raises
    FileNotFoundError at import — the desired fail-fast for a packaging bug, not a mid-scrape one.

    load_script is only ever called with the hardcoded literal names in main.py, but it still
    validates `name` is a bare identifier — cheap defense-in-depth so the interpolated `name` can
    never path-traverse (e.g. `../`, `/`) if a future caller ever passes dynamic input.
    """
    if not name.isidentifier():
        raise ValueError(f"invalid script name: {name!r}")
    # utf-8-sig (not plain utf-8): strips a leading BOM if a Windows editor ever saves a .js with
    # one, so no stray ﻿ prefixes the script. A no-op when there is no BOM. (Repo supports Windows
    # clones — see CLAUDE.md core.symlinks note.)
    source = (_SCRIPTS_DIR / f"{name}.js").read_text(encoding="utf-8-sig")
    # Trailing newline after the pragma is defensive — not required on Playwright 1.58.0 (verified),
    # but it keeps a wrapping ')' off the comment line if Playwright's eval internals ever change.
    return f"{source}\n//# sourceURL=scraper/browser_scripts/{name}.js\n"

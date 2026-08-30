#!/usr/bin/env python3
"""Fail on non-ASCII bytes in .properties files.

Java .properties are spec'd as ISO-8859-1, and that is what actually reads them here:
Spring Boot's OriginTrackedPropertiesLoader decodes with ISO_8859_1, and IntelliJ defaults
the format to the same. So a UTF-8 em dash written into one of these files renders as
mojibake ("a-circumflex" soup) for every reader -- the bytes on disk are fine, but nothing
in the toolchain decodes them as UTF-8. Comments are where this shows up in practice; a
non-ASCII byte in a *value* would additionally reach a bound property mis-decoded.

Editors and paste insert these characters silently, so this is a hook rather than a
convention. Detection is done on raw bytes in Python on purpose: macOS grep has no -P,
and a `grep -P` guard there fails open -- it reports a clean pass it never performed.

Invoked by pre-commit on staged .properties files; also runnable by hand:

    scripts/check-ascii-properties.py $(git ls-files '*.properties')
"""

import sys
import unicodedata

# Characters seen in practice, with the ASCII spelling to use instead. Anything not listed
# still fails -- this only makes the common cases self-explanatory in the error output.
SUGGESTIONS = {
    "—": "--",  # em dash
    "–": "-",  # en dash
    "→": "->",  # rightwards arrow
    "↔": "<->",  # left-right arrow
    "‘": "'",  # left single quote
    "’": "'",  # right single quote
    "“": '"',  # left double quote
    "”": '"',  # right double quote
    "…": "...",  # horizontal ellipsis
    " ": " ",  # no-break space
}


def describe(char: str) -> str:
    name = unicodedata.name(char, "unnamed character")
    label = f"U+{ord(char):04X} {name}"
    suggestion = SUGGESTIONS.get(char)
    return f"{label} -- write {suggestion!r} instead" if suggestion else label


def check(path: str) -> list[str]:
    """Return one human-readable complaint per offending character in `path`."""
    with open(path, "rb") as handle:
        raw = handle.read()

    if raw.isascii():
        return []

    # Decode for reporting only. These files are read as ISO-8859-1 in production, but
    # they are *authored* as UTF-8, so UTF-8 is what names the character the author typed.
    text = raw.decode("utf-8", errors="replace")
    return [
        f"{path}:{lineno}:{col}: non-ASCII {describe(char)}"
        for lineno, line in enumerate(text.splitlines(), start=1)
        for col, char in enumerate(line, start=1)
        if not char.isascii()
    ]


def main(paths: list[str]) -> int:
    problems = [problem for path in paths for problem in check(path)]
    if not problems:
        return 0

    print("\n".join(problems), file=sys.stderr)
    print(
        f"\n{len(problems)} non-ASCII character(s) in .properties files. These files are "
        "decoded as\nISO-8859-1 by Spring Boot and IntelliJ, so non-ASCII renders as "
        "mojibake. Use ASCII.",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

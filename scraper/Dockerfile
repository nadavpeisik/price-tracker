FROM python:3.12-slim

WORKDIR /app

# Stage 1: install third-party deps from pyproject.toml. Placeholders for every only-include
# path (main.py, models.py, sites/, browser_scripts/) are needed because hatchling requires them
# at wheel-build time; this wheel exists only to pull in deps. The heavy chromium-download layer
# stays cached across code changes (the real code is copied in stage 2).
# --only-binary=:all: forbids source distributions for the third-party graph, so no downloaded
# package can execute its own build script during the image build (docker:S8541, issue #165). The
# build fails loudly if some future transitive ships no wheel — that is the point. Transitive
# VERSIONS are still resolved fresh per build (docker:S8544, deliberately deferred: see #187).
# It does NOT block the local project below: the flag filters index candidates, and an explicitly
# given local path is still built via its PEP 517 backend. Verified by a --no-cache build (#165) —
# review keeps predicting otherwise, so don't "fix" it with --no-binary=price-tracker-scraper.
COPY pyproject.toml ./
RUN mkdir -p sites browser_scripts && \
    touch main.py models.py sites/__init__.py browser_scripts/__init__.py && \
    pip install --no-cache-dir --only-binary=:all: . && \
    rm -rf main.py models.py sites browser_scripts && \
    playwright install --with-deps chromium

# Stage 2: real source — only these layers invalidate on code change.
COPY main.py models.py ./
COPY sites ./sites
COPY browser_scripts ./browser_scripts
# No reinstall: uvicorn runs with app-dir "." so /app is first on sys.path and the real files
# shadow the empty site-packages placeholders (the long-standing single-file Dockerfile pattern).

EXPOSE 8001

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]

FROM python:3.12-slim

WORKDIR /app

# Dependency layer, installed from a hash-pinned lock (issue #187). Two guarantees:
#   --require-hashes     every downloaded file is verified against requirements.lock, so pip can
#                        only install an artifact whose hash was committed — a newer or compromised
#                        transitive cannot slip in on its own (docker:S8544). Not byte-identical
#                        across architectures: arm64 and amd64 legitimately take different wheels
#                        of the same locked version, both hashes being in the lock.
#   --only-binary=:all:  wheels only, so nothing executes a build script during the image build
#                        (docker:S8541, issue #165). Fails loudly if a package ships no wheel.
# The lock is multi-architecture (uv resolves universally), so one file serves linux/arm64 dev
# machines and linux/amd64 deploy targets. A per-arch lock proves nothing about the other one, and
# with our compiled deps (uvloop, httptools, pydantic-core, …) it fails there outright.
# Regenerate after ANY dependency change (see CLAUDE.md):
#   uv lock && uv export --no-dev --no-emit-project -o requirements.lock
# This layer stays cached across code changes; real source is copied below.
COPY requirements.lock ./
RUN pip install --no-cache-dir --require-hashes --only-binary=:all: -r requirements.lock && \
    playwright install --with-deps chromium

# Real source — only these layers invalidate on code change. The project itself is deliberately
# NOT pip-installed: uvicorn puts /app first on sys.path, so these files are imported directly.
# (This replaced a placeholder-file trick whose only purpose was to make `pip install .` resolve
# dependencies; installing the lock directly does that, so the trick and its build backend are gone.)
COPY main.py models.py ./
COPY sites ./sites
COPY browser_scripts ./browser_scripts

EXPOSE 8001

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]

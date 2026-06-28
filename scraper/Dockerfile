FROM python:3.12-slim

WORKDIR /app

# Stage 1: install deps from pyproject.toml. Placeholders for every only-include
# path (main.py, models.py, sites/) are needed because hatchling requires them to
# exist at wheel-build time. The real code is copied + re-installed in stage 2, so
# this heavy chromium-download layer stays cached across code changes.
COPY pyproject.toml ./
RUN mkdir -p sites && \
    touch main.py models.py sites/__init__.py && \
    pip install --no-cache-dir . && \
    rm -rf main.py models.py sites && \
    playwright install --with-deps chromium

# Stage 2: real source — only these layers invalidate on code change.
COPY main.py models.py ./
COPY sites ./sites
# Re-install the real code over the stage-1 placeholders in site-packages so imports resolve
# however the process is launched (uvicorn app-dir, pytest, a console entrypoint), not just from
# the /app cwd. --force-reinstall is required: the version is unchanged from stage 1, so a plain
# `pip install` would no-op ("already satisfied") and leave the empty placeholders in place.
RUN pip install --no-cache-dir --no-deps --force-reinstall .

EXPOSE 8001

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8001"]

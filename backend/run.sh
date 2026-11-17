#!/usr/bin/env bash
# One-command start for macOS / Linux. Creates the venv, installs deps, seeds .env, runs.
set -euo pipefail
cd "$(dirname "$0")"

PYTHON="${PYTHON:-python3}"
if ! command -v "${PYTHON}" >/dev/null 2>&1; then
  echo "python3 not found. Install Python 3.11+ and retry." >&2
  exit 1
fi

if [[ ! -d .venv ]]; then
  echo "Creating .venv …"
  "${PYTHON}" -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate

python -m pip install --quiet --upgrade pip
python -m pip install --quiet -r requirements.txt

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created backend/.env — put your GEMINI_API_KEY in it, then run this script again."
fi

if ! grep -qE '^GEMINI_API_KEY=.+' .env; then
  echo
  echo "WARNING: GEMINI_API_KEY is empty in backend/.env."
  echo "         The server will start, /health will report gemini_key_present=false,"
  echo "         and delegated tasks will fail until you add a key."
  echo
fi

# 0.0.0.0 so a phone on the same Wi-Fi can reach it; 127.0.0.1 would be PC-only.
export SARA_HOST="${SARA_HOST:-0.0.0.0}"
export SARA_PORT="${SARA_PORT:-8000}"
exec python -m app.main
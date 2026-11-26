#!/usr/bin/env bash
# Generates local admin and ingest keys into .env.local (permissions 600).
# Keys are not printed. Read them with: grep KEY= .env.local
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env.local ] && [ "${1:-}" != "--force" ]; then
  echo ".env.local already exists (use --force to rotate keys)."
  exit 0
fi

rand() { openssl rand -base64 "$1" | tr '+/' '-_' | tr -d '=\n'; }
sha() { printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; }

ADMIN="pa_admin_$(rand 32)"
INGEST="pa_ingest_$(rand 32)"
SESSION="$(rand 48)"
GEMINI_EXISTING="$( [ -f .env.local ] && grep '^GEMINI_API_KEY=' .env.local | cut -d= -f2- || true )"

umask 077
cat > .env.local <<EOF
# Local secrets for PulseAgent. Git-ignored. Do not share.
PULSEAGENT_ADMIN_KEY=$ADMIN
PULSEAGENT_INGEST_KEY=$INGEST
APP_ADMIN_KEY_SHA256=$(sha "$ADMIN")
APP_INGEST_KEY_SHA256=$(sha "$INGEST")
APP_SESSION_SECRET=$SESSION
APP_ENCRYPTION_KEY=$(rand 48)
# Paste your free key from https://aistudio.google.com/apikey (leave empty for offline mode)
GEMINI_API_KEY=$GEMINI_EXISTING
EOF
chmod 600 .env.local
echo "Wrote .env.local with new keys (permissions 600)."

#!/usr/bin/env bash
# Generates production keys (separate from local ones) into .env.production (git-ignored, mode 600).
# Paste only the *_SHA256 hashes into Render. Keep the plain keys in a password manager.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env.production ] && [ "${1:-}" != "--force" ]; then
  echo ".env.production already exists (use --force to rotate production keys)."
  exit 0
fi

rand() { openssl rand -base64 "$1" | tr '+/' '-_' | tr -d '=\n'; }
sha() { printf '%s' "$1" | shasum -a 256 | cut -d' ' -f1; }

ADMIN="pa_admin_$(rand 32)"
INGEST="pa_ingest_$(rand 32)"

umask 077
cat > .env.production <<EOF
# PRODUCTION keys for PulseAgent. Git-ignored. Store the plain keys in a password manager.
# Plain keys: you sign in with ADMIN; apps and webhooks use INGEST.
PROD_ADMIN_KEY=$ADMIN
PROD_INGEST_KEY=$INGEST
# Paste these two hashes into Render's environment settings:
APP_ADMIN_KEY_SHA256=$(sha "$ADMIN")
APP_INGEST_KEY_SHA256=$(sha "$INGEST")
EOF
chmod 600 .env.production
echo "Wrote .env.production (permissions 600). View it with: cat .env.production"

#!/usr/bin/env bash
# Builds and starts Prometheus, the PulseAgent backend and the demo payment service locally.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

[ -f .env.local ] || scripts/gen-keys.sh
set -a; source .env.local; set +a
if [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

mkdir -p .data/logs .data/prometheus

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "Building frontend..."
  (cd frontend && npm ci --no-audit --no-fund --silent && npm run build --silent)
  echo "Building backend, SDK and demo app..."
  mvn -q -DskipTests package
fi

scripts/stop.sh >/dev/null 2>&1 || true

PROM_CONFIG="prometheus/prometheus.yml"
if [ -n "${GC_REMOTE_WRITE_URL:-}" ] && [ -n "${GC_USERNAME:-}" ] && [ -n "${GC_WRITE_TOKEN:-}" ]; then
  # Forward metrics to Grafana Cloud. The token goes to a 600 file, never into the config text.
  ( umask 077; printf '%s' "$GC_WRITE_TOKEN" > .data/gc_write_token )
  sed "s#^  - rules.yml#  - $ROOT/prometheus/rules.yml#" prometheus/prometheus.yml > .data/prometheus.yml
  cat >> .data/prometheus.yml <<EOF

remote_write:
  - url: $GC_REMOTE_WRITE_URL
    basic_auth:
      username: "$GC_USERNAME"
      password_file: $ROOT/.data/gc_write_token
EOF
  PROM_CONFIG=".data/prometheus.yml"
  echo "Forwarding metrics to Grafana Cloud"
fi

echo "Starting Prometheus on 127.0.0.1:9090"
prometheus --config.file="$PROM_CONFIG" --storage.tsdb.path=.data/prometheus \
  --web.listen-address=127.0.0.1:9090 > .data/logs/prometheus.log 2>&1 &
echo $! > .data/prometheus.pid

echo "Starting PulseAgent on http://127.0.0.1:8080"
APP_SECURE_COOKIES=false APP_CODE_REPO_PATH="$ROOT" APP_KNOWLEDGE_DIR="$ROOT/knowledge" APP_DATA_DIR="$ROOT/.data" \
  java -XX:MaxRAMPercentage=50 -jar backend/target/pulseagent.jar --server.address=127.0.0.1 > .data/logs/backend.log 2>&1 &
echo $! > .data/backend.pid

echo "Starting demo payment-service on 127.0.0.1:8081"
PULSEAGENT_COMMIT_SHA="$(git rev-parse HEAD 2>/dev/null || true)" \
  java -jar demo-app/target/demo-payment-app.jar > .data/logs/demo.log 2>&1 &
echo $! > .data/demo.pid

for i in $(seq 1 60); do
  curl -fs http://127.0.0.1:8080/api/health >/dev/null 2>&1 && break
  sleep 1
done
echo
echo "Ready: open http://127.0.0.1:8080 and sign in with PULSEAGENT_ADMIN_KEY from .env.local"
echo "Break the demo:  scripts/chaos.sh on      Fix it:  scripts/chaos.sh off"
echo "Logs: .data/logs/   Stop: scripts/stop.sh"

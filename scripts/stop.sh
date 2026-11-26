#!/usr/bin/env bash
cd "$(dirname "$0")/.."
for name in demo backend prometheus; do
  if [ -f ".data/$name.pid" ]; then
    kill "$(cat ".data/$name.pid")" 2>/dev/null && echo "Stopped $name"
    rm -f ".data/$name.pid"
  fi
done

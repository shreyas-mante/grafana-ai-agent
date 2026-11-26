#!/usr/bin/env bash
# Makes the demo payment gateway slow ("on") or healthy ("off").
set -euo pipefail
mode="${1:-on}"
[[ "$mode" == "on" || "$mode" == "off" ]] || { echo "usage: $0 on|off"; exit 1; }
curl -fsS -X POST "http://127.0.0.1:8081/chaos/$mode"
echo

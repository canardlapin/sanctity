#!/usr/bin/env bash
# Retained v4g gate plus replay of the seven regressions against v4f.
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-$task_root/target/console-browsers}"
npm ci --prefix tests/console --ignore-scripts --no-audit --no-fund --cache "$task_root/target/npm-cache"
node tests/console/node_modules/playwright/cli.js install chromium
node tests/console/run.js

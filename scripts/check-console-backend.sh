#!/usr/bin/env bash
# Real browser -> authenticated HTTP -> disposable PostgreSQL rehearsal.
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
mkdir -p target
task_workspace="$(mktemp -d "$task_root/target/console-backend.XXXXXX")"
task_container=""
cleanup() {
  if [[ -n "$task_container" ]]; then
    docker logs "$task_container" > "$task_workspace/postgres-server.log" 2>&1 || true
    docker rm -fv "$task_container" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
printf 'Console backend evidence: %s\n' "$task_workspace"
export PLAYWRIGHT_BROWSERS_PATH="${PLAYWRIGHT_BROWSERS_PATH:-$task_root/target/console-browsers}"
npm ci --prefix tests/console --ignore-scripts --no-audit --no-fund --cache "$task_root/target/npm-cache"
node tests/console/node_modules/playwright/cli.js install chromium
task_image="${SANCTITY_PG_IMAGE:-postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24}"
task_container="$(docker run -d --label io.sanctity.purpose=console-rehearsal \
  -e POSTGRES_USER=sanctity_test -e POSTGRES_PASSWORD=ephemeral_test_only -e POSTGRES_DB=sanctity_test \
  -p 127.0.0.1::5432 "$task_image")"
task_attempts=0
until docker exec "$task_container" pg_isready -h 127.0.0.1 -U sanctity_test -d sanctity_test >/dev/null 2>&1; do
  task_attempts=$((task_attempts + 1))
  if [[ "$task_attempts" -ge 120 ]]; then
    echo "Disposable PostgreSQL did not become ready." >&2
    exit 1
  fi
  sleep 0.25
done
task_port="$(docker port "$task_container" 5432/tcp)"
export SANCTITY_JDBC_URL="jdbc:postgresql://127.0.0.1:${task_port##*:}/sanctity_test"
export SANCTITY_DB_USER=sanctity_test SANCTITY_DB_PASSWORD=ephemeral_test_only SANCTITY_TEST_DISPOSABLE=1
export SANCTITY_CONSOLE_REHEARSAL_OUTPUT="$task_workspace"
export SANCTITY_CONSOLE_REHEARSAL_ROOT="$task_root"
printf '%s\n' "$task_image" > "$task_workspace/postgres-image.txt"
docker exec "$task_container" postgres --version > "$task_workspace/postgres-version.txt"
sbt 'integration/testOnly sanctity.postgres.ConsoleSuite' \
  'integration/Test/runMain sanctity.postgres.ConsoleRehearsal' 2>&1 | tee "$task_workspace/backend.log"
node tests/console/rehearsal-receipt.js "$task_workspace"

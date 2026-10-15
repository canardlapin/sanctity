#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
export SANCTITY_MRI_PYTHON="${SANCTITY_MRI_PYTHON:-$task_root/target/mri-python/bin/python}"
if [[ ! -x "$SANCTITY_MRI_PYTHON" ]]; then
  echo "Run scripts/setup-mri-python.sh first, or set SANCTITY_MRI_PYTHON to the pinned recipe environment." >&2
  exit 1
fi
mkdir -p target
workspace="$(mktemp -d "$task_root/target/postgres-check.XXXXXX")"
container=""
cleanup() {
  if [[ -n "$container" ]]; then
    docker logs "$container" > "$workspace/postgres-server.log" 2>&1 || true
    docker rm -fv "$container" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# Official PostgreSQL 17 multi-platform image, pinned to the tested manifest.
image="${SANCTITY_PG_IMAGE:-postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24}"
container="$(docker run -d --label io.sanctity.purpose=integration-check \
  -e POSTGRES_USER=sanctity_test -e POSTGRES_PASSWORD=ephemeral_test_only -e POSTGRES_DB=sanctity_test \
  -p 127.0.0.1::5432 "$image")"

ready() {
  local attempts=0
  until docker exec "$container" pg_isready -h 127.0.0.1 -U sanctity_test -d sanctity_test >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [[ "$attempts" -ge 120 ]]; then
      docker logs "$container"
      return 1
    fi
    sleep 0.25
  done
}
ready
port="$(docker port "$container" 5432/tcp)"
export SANCTITY_JDBC_URL="jdbc:postgresql://127.0.0.1:${port##*:}/sanctity_test"
export SANCTITY_DB_USER=sanctity_test
export SANCTITY_DB_PASSWORD=ephemeral_test_only
export SANCTITY_DB_SCHEMA=sanctity_demo
export SANCTITY_TEST_DISPOSABLE=1

docker exec "$container" postgres --version | tee "$workspace/postgres-version.txt"
printf '%s\n' "$image" > "$workspace/postgres-image.txt"
if [[ -n "${SANCTITY_INTEGRATION_SUITES:-}" ]]; then
  printf 'Selected integration suites: %s\n' "$SANCTITY_INTEGRATION_SUITES"
  sbt check "integration/testOnly $SANCTITY_INTEGRATION_SUITES" "app/runMain sanctity.app.PostgresDemo stage \"$workspace/demo\""
else
  sbt check 'integration/test' "app/runMain sanctity.app.PostgresDemo stage \"$workspace/demo\""
fi

# Stop the server without a clean database shutdown, then start it on the same volume.
docker kill --signal=KILL "$container" >/dev/null
docker start "$container" >/dev/null
ready
port="$(docker port "$container" 5432/tcp)"
export SANCTITY_JDBC_URL="jdbc:postgresql://127.0.0.1:${port##*:}/sanctity_test"

sbt "app/runMain sanctity.app.PostgresDemo resume \"$workspace/demo\"" \
    "app/runMain sanctity.app.PostgresDemo resume \"$workspace/demo\""

counts="$(docker exec "$container" psql -U sanctity_test -d sanctity_test -Atc \
  'SELECT (SELECT count(*) FROM sanctity_demo.release),(SELECT count(*) FROM sanctity_demo.acceptance_receipt),(SELECT count(*) FROM sanctity_demo.audit),(SELECT count(*) FROM sanctity_demo.outbox)')"
if [[ "$counts" != "2|2|2|2" ]]; then
  printf 'Unexpected publication counts: %s\n' "$counts" >&2
  exit 1
fi

# A database backup must not revive authority revoked after the backup.
sbt "app/runMain sanctity.app.RecoveryDemo grant \"$workspace/demo\""
mkdir -p "$workspace/pgbin" "$workspace/tmp"
export SANCTITY_TEST_CONTAINER="$container"
export SANCTITY_TEST_WORKSPACE="$workspace"
export SANCTITY_TEST_IMAGE="$image"
export PGHOST=127.0.0.1 PGUSER=sanctity_test PGPASSWORD=ephemeral_test_only PGDATABASE=sanctity_test
export TMPDIR="$workspace/tmp"
for command in pg_dump pg_restore psql; do
  cat > "$workspace/pgbin/$command" <<'CLIENT'
#!/usr/bin/env bash
set -euo pipefail
exec docker run --rm -i --network "container:$SANCTITY_TEST_CONTAINER" \
  -v "$SANCTITY_TEST_WORKSPACE:$SANCTITY_TEST_WORKSPACE" \
  -e PGHOST -e PGUSER -e PGPASSWORD -e PGDATABASE "$SANCTITY_TEST_IMAGE" "$(basename "$0")" "$@"
CLIENT
  chmod +x "$workspace/pgbin/$command"
done
export PATH="$workspace/pgbin:$PATH"
scripts/backup-postgres.sh "$workspace/demo/storage" "$workspace/backup"
sbt "app/runMain sanctity.app.RecoveryDemo revoke \"$workspace/demo\"" \
    "app/runMain sanctity.app.RecoveryCli capture-policy \"$workspace/current-policy.json\""
docker exec "$container" createdb -U sanctity_test sanctity_restore
export PGDATABASE=sanctity_restore
scripts/restore-postgres.sh "$workspace/backup" "$workspace/restored/storage"
cp "$workspace/demo/checkpoint.json" "$workspace/restored/checkpoint.json"
cp "$workspace/demo/guest.json" "$workspace/restored/guest.json"
export SANCTITY_JDBC_URL="jdbc:postgresql://127.0.0.1:${port##*:}/sanctity_restore"
policy_digest="$(cat "$workspace/current-policy.json.sha256")"
sbt "app/runMain sanctity.app.RecoveryDemo closed \"$workspace/restored\"" \
    "app/runMain sanctity.app.RecoveryCli reconcile \"$workspace/current-policy.json\" $policy_digest \"$workspace/restored/storage\"" \
    "app/runMain sanctity.app.RecoveryDemo verify \"$workspace/restored\"" \
    "app/runMain sanctity.app.PostgresDemo resume \"$workspace/restored\""
printf 'Coordinated restore retained scientific releases and preserved subsequent revocation.\n'

printf 'PostgreSQL integration and crash/retry demonstration passed. Evidence: %s\n' "$workspace"

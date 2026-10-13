#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
workspace="$(mktemp -d "$task_root/target/publication-recovery.XXXXXX")"
image="${SANCTITY_PG_IMAGE:-postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24}"
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
container="$(docker run -d --label io.sanctity.purpose=publication-recovery \
  -e POSTGRES_USER=sanctity_test -e POSTGRES_PASSWORD=ephemeral_test_only -e POSTGRES_DB=sanctity_test \
  -p 127.0.0.1::5432 "$image")"
ready() {
  local attempts=0
  until docker exec "$container" pg_isready -h 127.0.0.1 -U sanctity_test -d sanctity_test >/dev/null 2>&1; do
    attempts=$((attempts + 1))
    if [[ "$attempts" -ge 120 ]]; then docker logs "$container"; return 1; fi
    sleep 0.25
  done
  local port
  port="$(docker port "$container" 5432/tcp)"
  export SANCTITY_JDBC_URL="jdbc:postgresql://127.0.0.1:${port##*:}/sanctity_test"
}
ready
export SANCTITY_DB_USER=sanctity_test SANCTITY_DB_PASSWORD=ephemeral_test_only SANCTITY_DB_SCHEMA=sanctity_publication
docker exec "$container" postgres --version > "$workspace/postgres-version.txt"
printf '%s\n' "$image" > "$workspace/postgres-image.txt"
sbt "app/runMain sanctity.app.PublicationRecoveryDemo stage $workspace/demo"
docker kill --signal=KILL "$container" >/dev/null
docker start "$container" >/dev/null
ready
sbt "app/runMain sanctity.app.PublicationRecoveryDemo resume $workspace/demo" \
    "app/runMain sanctity.app.PublicationRecoveryDemo resume $workspace/demo"
counts="$(docker exec "$container" psql -U sanctity_test -d sanctity_test -Atc \
  'SELECT (SELECT count(*) FROM sanctity_publication.processing_publication),(SELECT count(*) FROM sanctity_publication.release),(SELECT count(*) FROM sanctity_publication.outbox)')"
[[ "$counts" == '1|3|3' ]] || { printf 'Unexpected publication counts: %s\n' "$counts"; exit 1; }
mkdir -p "$workspace/pgbin" "$workspace/tmp"
export SANCTITY_TEST_CONTAINER="$container" SANCTITY_TEST_WORKSPACE="$workspace" SANCTITY_TEST_IMAGE="$image"
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
sbt "app/runMain sanctity.app.PublicationRecoveryDemo revoke $workspace/demo" \
    "app/runMain sanctity.app.RecoveryCli capture-policy $workspace/current-policy.json"
docker exec "$container" createdb -U sanctity_test sanctity_restore
export PGDATABASE=sanctity_restore
scripts/restore-postgres.sh "$workspace/backup" "$workspace/restored/storage"
cp "$workspace/demo/publication-checkpoint.json" "$workspace/restored/publication-checkpoint.json"
port="$(docker port "$container" 5432/tcp)"
export SANCTITY_JDBC_URL="jdbc:postgresql://127.0.0.1:${port##*:}/sanctity_restore"
policy_digest="$(cat "$workspace/current-policy.json.sha256")"
sbt "app/runMain sanctity.app.PublicationRecoveryDemo closed $workspace/restored" \
    "app/runMain sanctity.app.RecoveryCli reconcile $workspace/current-policy.json $policy_digest $workspace/restored/storage" \
    "app/runMain sanctity.app.PublicationRecoveryDemo revoked $workspace/restored"
restored="$(docker exec "$container" psql -U sanctity_test -d sanctity_restore -Atc \
  'SELECT (SELECT count(*) FROM sanctity_publication.processing_publication),(SELECT count(*) FROM sanctity_publication.release),(SELECT count(*) FROM sanctity_publication.outbox)')"
[[ "$restored" == "$counts" ]] || { printf 'Restored publication counts differ: %s\n' "$restored"; exit 1; }
printf 'Publication crash/retry and current-policy restore checks passed. Evidence: %s\n' "$workspace"

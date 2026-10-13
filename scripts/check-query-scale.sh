#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$task_root"
workspace="$(mktemp -d "$task_root/target/query-scale.XXXXXX")"
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
container="$(docker run -d --label io.sanctity.purpose=query-scale \
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
export SANCTITY_DB_USER=sanctity_test SANCTITY_DB_PASSWORD=ephemeral_test_only
export SANCTITY_DB_SCHEMA=sanctity_scale SANCTITY_TEST_DISPOSABLE=1
python3 - "$workspace" "$image" <<'PY'
import hashlib,json,os,platform,subprocess,sys
from pathlib import Path
root=Path.cwd()
files=sorted(p for directory in ('modules','scripts','project') for p in root.joinpath(directory).rglob('*')
             if p.is_file() and 'target' not in p.parts and '__pycache__' not in p.parts)
files.append(root/'build.sbt')
fingerprints={str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
report={'revision':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
        'sourceFingerprint':hashlib.sha256(json.dumps(fingerprints,sort_keys=True).encode()).hexdigest(),
        'files':fingerprints,'postgresImage':sys.argv[2],
        'hardware':{'platform':platform.platform(),'machine':platform.machine(),'logicalCpus':os.cpu_count(),
                    'docker':subprocess.check_output(['docker','info','--format','{{json .NCPU}} {{json .MemTotal}} {{json .Architecture}} {{json .ServerVersion}}'],text=True)},
        'command':'bash scripts/check-query-scale.sh',
        'snapshot':os.environ.get('SANCTITY_SCALE_SNAPSHOT'),
        'snapshotDumpSha256':hashlib.sha256(Path(os.environ['SANCTITY_SCALE_SNAPSHOT'],'catalog.dump').read_bytes()).hexdigest()
          if os.environ.get('SANCTITY_SCALE_SNAPSHOT') else None}
Path(sys.argv[1],'environment.json').write_text(json.dumps(report,indent=2)+'\n')
PY
printf 'Benchmark workspace: %s\n' "$workspace"
if [[ -n "${SANCTITY_SCALE_SNAPSHOT:-}" ]]; then
  cp -R "$SANCTITY_SCALE_SNAPSHOT/data" "$workspace/data"
  docker exec -i "$container" pg_restore -U sanctity_test -d sanctity_test --exit-on-error < "$SANCTITY_SCALE_SNAPSHOT/catalog.dump"
  docker exec "$container" psql -U sanctity_test -d sanctity_test -c 'ANALYZE' >/dev/null
else
  sbt "integration/Test/runMain sanctity.postgres.QueryBenchmark stage $workspace/data"
fi
docker exec "$container" pg_dump -U sanctity_test -d sanctity_test -Fc > "$workspace/catalog.dump"
docker restart "$container" >/dev/null
ready
sbt "integration/Test/runMain sanctity.postgres.QueryBenchmark measure $workspace/data"
python3 - "$workspace" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
report=json.loads((root/'data/report.json').read_text())
report['environment']=json.loads((root/'environment.json').read_text())
(root/'report.json').write_text(json.dumps(report,indent=2)+'\n')
print('Complete report:',root/'report.json')
PY

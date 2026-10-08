#!/usr/bin/env bash
set -euo pipefail
# Restore only into a fresh database while application processes are stopped.
# Leaves the repository CLOSED until RecoveryCli reconciles trusted current policy.
bundle="${1:?Usage: restore-postgres.sh BUNDLE NEW_STORAGE}"
storage="${2:?Usage: restore-postgres.sh BUNDLE NEW_STORAGE}"
[[ ! -e "$storage" ]] || { printf 'Storage destination already exists\n' >&2; exit 2; }
schema="$(python3 - "$bundle" <<'PY'
import hashlib,json,pathlib,re,sys
bundle=pathlib.Path(sys.argv[1]);record=json.loads((bundle/'bundle.json').read_text())
if record['version']!=1 or not re.fullmatch('[a-z][a-z0-9_]{0,62}',record['schema']): raise RuntimeError('invalid bundle')
for name,expected in record['files'].items():
    p=(bundle/name).resolve()
    if not p.is_relative_to(bundle.resolve()) or not p.is_file() or p.is_symlink(): raise RuntimeError('unsafe bundle entry')
    with p.open('rb') as f: digest=hashlib.file_digest(f,'sha256').hexdigest()
    if digest!=expected['sha256'] or p.stat().st_size!=expected['size']: raise RuntimeError('bundle integrity failure')
print(record['schema'])
PY
)"
existing="$(psql -X -At -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM pg_namespace WHERE nspname='$schema'")"
[[ "$existing" == 0 ]] || { printf 'Target schema must not exist\n' >&2; exit 2; }
restore_sql="$(mktemp "${TMPDIR:-/tmp}/sanctity-restore.XXXXXX")"
trap 'rm -f "$restore_sql"' EXIT
pg_restore --no-owner --no-acl --file="$restore_sql" "$bundle/catalog.dump"
{
  printf 'BEGIN;\n'
  cat "$restore_sql"
  printf 'UPDATE "%s".repository_state SET recovery_required=TRUE;\nCOMMIT;\n' "$schema"
} | psql -X -v ON_ERROR_STOP=1 >/dev/null
python3 - "$bundle/storage" "$storage" <<'PY'
import pathlib,shutil,sys
shutil.copytree(sys.argv[1],sys.argv[2])
PY
printf 'Restored with access CLOSED. Reconcile trusted current policy and verify blobs before serving.\n'

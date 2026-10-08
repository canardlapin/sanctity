#!/usr/bin/env bash
set -euo pipefail
# Requires pg_dump/pg_restore/psql matching the server major version.
# PostgreSQL connection uses standard PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD or service files.
schema="${SANCTITY_DB_SCHEMA:?Set SANCTITY_DB_SCHEMA}"
storage="${1:?Usage: backup-postgres.sh STORAGE NEW_BUNDLE}"
bundle="${2:?Usage: backup-postgres.sh STORAGE NEW_BUNDLE}"
[[ "$schema" =~ ^[a-z][a-z0-9_]{0,62}$ ]] || exit 2
[[ ! -e "$bundle" ]] || { printf 'Bundle already exists\n' >&2; exit 2; }
mkdir "$bundle"
# pg_dump captures one database snapshot. Blobs are installed before catalog publication
# and this implementation never collects them, so copying afterwards retains that snapshot.
pg_dump --format=custom --no-owner --no-acl --schema="$schema" --file="$bundle/catalog.dump"
python3 - "$storage" "$bundle" "$schema" <<'PY'
import hashlib,json,pathlib,shutil,sys
source,bundle,schema=pathlib.Path(sys.argv[1]),pathlib.Path(sys.argv[2]),sys.argv[3]
objects=bundle/'storage'/'objects';objects.mkdir(parents=True)
records={}
for p in sorted((source/'objects').glob('*/*')):
    if not p.is_file() or p.is_symlink(): raise RuntimeError('unexpected object entry')
    target=objects/p.parent.name/p.name;target.parent.mkdir(exist_ok=True)
    shutil.copyfile(p,target)
    digest=hashlib.file_digest(target.open('rb'),'sha256').hexdigest()
    if digest!=p.name or p.parent.name!=digest[:2]: raise RuntimeError('object integrity failure')
    records[str(target.relative_to(bundle))]={'sha256':digest,'size':target.stat().st_size}
dump=bundle/'catalog.dump'
records['catalog.dump']={'sha256':hashlib.file_digest(dump.open('rb'),'sha256').hexdigest(),'size':dump.stat().st_size}
(bundle/'bundle.json').write_text(json.dumps({'version':1,'schema':schema,'files':records},sort_keys=True))
PY
printf 'Backup complete: %s\n' "$bundle"

#!/usr/bin/env bash
# Builds immutable specimens for ROUND from the project UI sources.
set -euo pipefail
H=$(cd -- "$(dirname -- "$0")" && pwd)
R=${1:?usage: build.sh ROUND}
UI=/Users/bbuchsbaum/code/scala/sanctity/modules/http/src/main/resources/sanctity/ui
O="$H/out/$R"
[[ -e "$O" ]] && { echo "refusing existing $O" >&2; exit 1; }
mkdir -p "$O"
cp "$UI"/index.html "$UI"/app.css "$UI"/app.js "$UI"/demo.js "$H/specimens/drive.js" "$O/"
python3 - "$O" "$H/specimens/specs.json" <<'PY'
import json, sys, pathlib
out = pathlib.Path(sys.argv[1]); specs = json.load(open(sys.argv[2]))
index = (out / 'index.html').read_text()
assert '<script src="app.js"></script>' in index
for name, spec in specs.items():
    (out / f'spec-{name}.js').write_text(f"window.SPEC = {json.dumps(spec)};\ntry {{ localStorage.clear(); sessionStorage.clear(); }} catch (e) {{}}\nlocation.hash = window.SPEC.hash;\n")
    page = index.replace('<script src="app.js"></script>',
      f'<script src="spec-{name}.js"></script><script src="demo.js"></script><script src="app.js"></script><script src="drive.js"></script>')
    (out / f'{name}.html').write_text(page)
print('built', len(specs), 'specimens')
PY
(cd /Users/bbuchsbaum/code/scala/sanctity && { git rev-parse HEAD; git status --short -- modules/http/src/main/resources/sanctity/ui; shasum -a 256 "$UI"/*; }) > "$O/SOURCE.txt"

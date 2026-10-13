#!/usr/bin/env bash
set -uo pipefail
H=${HARNESS:-$(cd -- "$(dirname -- "$0")/.." && pwd)}
export HARNESS="$H"
R=${1:?usage: bash checks/run.sh ROUND}
[[ "$R" =~ ^[A-Za-z0-9][A-Za-z0-9_-]*$ ]] || { echo "bad round" >&2; exit 2; }
O="${CONSOLE_OUTPUT_ROOT:-$H/../../target/console}/$R"; status=0
node "$H/checks/ui.js" "$R" || status=1
node "$H/checks/behaviour.js" "$R" || status=1
node "$H/scripts/checks/cls.js" "$O/release.html;$O/correction.html;$O/connect.html" || status=1
node -e "for (const f of ['app.js','demo.js']) new Function(require('fs').readFileSync('$O/'+f,'utf8')); console.log('syntax PASS')" || status=1
echo "gate exit $status"
exit $status

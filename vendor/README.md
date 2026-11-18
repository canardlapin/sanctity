# bids4s source pin

`bids4s-source.zip` contains the exact upstream source tree, including unpublished
changes required by Sanctity. `bids4s-source.json` records the upstream base Git
revision and each source digest. The archive contains the Apache-2.0 license.
`project/bids4s-source.properties` pins the archive SHA-256, verified at build load.
No sibling checkout is needed for the default build.

To develop against a sibling checkout, use `sbt -Dsanctity.bids4s.path=../bids4s`.
After upstream changes pass JVM/JS tests and public consumer checks, run
`python3 scripts/source-snapshot.py pin --upstream ../bids4s` and verify the default
build. This intentionally uses a source pin while the required upstream changes
remain unpublished; the base Git revision alone is insufficient to reproduce them.

## NiiVue browser dependency

The HTTP console packages the standalone ES module from `@niivue/niivue`
0.69.0. `python3 scripts/vendor-niivue.py` checks the npm archive's SHA-512,
decodes its published module into a same-origin asset and collects notices
from the dependency versions in the upstream lockfile. The resulting module
SHA-256 is `bcb456b66a26e20126b7257c2b6071f48b347b7f910f99bca26721a71a96cbed`.
The distribution includes `NOTICE-niivue.txt`. Ordinary builds and browser
sessions use the checked-in files offline.

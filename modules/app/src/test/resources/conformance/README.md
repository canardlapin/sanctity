# Independent BIDS conformance fixtures

The unmodified `ds001` and `ds114` files come from
[bids-standard/bids-examples at 5e00d47](https://github.com/bids-standard/bids-examples/tree/5e00d47b6f1db106dda1b776d5243eb41b4cb95b).
`source.json` records the full commit, all 309 file SHA-256 values and the ZIP
identity. The archive is a compact transport of exact upstream bytes, including
zero-byte image placeholders, README and citation material. It is not a usable
imaging dataset. The upstream tree supplies no root license file; no additional
license for those examples is asserted here.

Reviewed expectations are independent of Sanctity's fixture generator:

- ds001: 135 files; three subject-01 BOLD runs; root TaskName and TR 2 seconds;
  run-specific event tables.
- ds114: 174 files; session-specific paths; root TR 2.5 seconds for
  finger/foot/lips; inherited finger/foot/lips events; local line-bisection events;
  root bval/bvec companions for DWI.
- Three explicit mutations in `cases.json` remove the dataset Name, make TR
  negative, or supply both TR and VolumeTiming. Both implementations must reject
  the corresponding supported metadata condition.

`ConformanceSuite` runs offline. It verifies every source digest, the official
schema digest, the reviewed inheritance/companion facts, and the recorded
validator error signatures alongside the local semantic checks.

## Reproduce the external comparison

With Deno **2.5.4** available, from the repository root:

```sh
python3 scripts/check-bids-conformance.py --deno /path/to/deno
```

The runner uses **@bids/validator 3.0.2**, frozen `deno.lock` dependencies, and
vendored **@bids/schema 1.2.2 / BIDS 1.11.1** bytes. The schema is serialized from
`schema` exported by `jsr:@bids/schema@1.2.2`; `validator.json` binds the exact
uncompressed SHA-256. It overrides any inherited BIDS_SCHEMA setting. The schema
is attributed to BIDS Contributors under [CC BY 4.0](schema-LICENSE.txt), from
the [BIDS specification](https://github.com/bids-standard/bids-specification/tree/v1.11.1).

Empty image issues alone are ignored via `validator-config.json`, and NIfTI
header checks are disabled because these examples contain no image payload.
Every TSV row is checked. Other warnings are retained in the full reports.
Both untouched examples have no remaining errors. The three negative cases
produce the exact error code/subcode sets in `cases.json` and exit status 16.
`official-results.json` records the initial comparison and raw report digests;
new raw reports and comparison records go into a fresh `target/conformance-*`
directory. Report hashes can vary with issue ordering; the reproducible contract
is the checked error signature, explicit schema and source identities.

This proves overlapping filename, metadata and companion behavior. It does not
qualify image geometry, payload validity, pipelines, all BIDS modalities, all
inheritance corner cases, or equivalence to the official validator. The examples'
authored BIDSVersion strings are preserved independently of the selected
comparison schema. Official validation is an explicit developer check; it is
not run during repository acceptance or ordinary offline tests.

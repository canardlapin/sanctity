# Authenticated repository API

This slice makes the existing release and curation contracts available through an authenticated HTTP boundary. It includes a loopback http4s server, operator commands and an [OpenAPI 3.1 contract](http-api.openapi.json). Institutional login, memberships and delegated execution credentials remain separate work.

The server also serves the browser console at `/`. Connect with an operator-issued
token and dataset ID; its release, correction, query and access screens use the
same authenticated `/v1` routes. `/?demo` uses synthetic browser fixtures. The
static shell/assets are public; repository data and mutations require the API's
current authority checks. See the [v4g baseline and adoption gates](implementation-plan.md#console-baseline-and-adoption)
for regression contracts, backend proof and remaining accessibility qualification.

## Console checks and VoiceOver exercise

Run `bash scripts/check-console.sh` for the recovered v4g browser gate and the
seven-failure replay against the retained v4f build. It installs locked test-only
dependencies, builds specimens from current UI sources and writes logs plus a
source-hashed receipt under `target/console-check.*/`. The original
[review evidence](evidence/console/recovery-manifest.json) remains unchanged.
These checks use synthetic fixtures; the PostgreSQL runner separately executes
ConsoleSuite against the real HTTP/backend contracts.

Run `bash scripts/check-console-backend.sh` for the complete browser-to-backend
rehearsal. It requires Docker, Node and the existing Java/sbt build prerequisites;
the script installs the locked browser dependencies and Chromium. It starts a
pinned disposable PostgreSQL container, imports invented BIDS data with real
NIfTI headers, and serves the normal console/API on an ephemeral loopback port.
Three isolated browser sessions use short-lived credentials passed to the
browser runner over stdin. The rehearsal uses normal API requests and production
CSP, with no demo transport or request interception.

The scenarios cover authentication errors, metadata/relationships, subject-level
sidecar acceptance, a frozen age query, conflicting edits by two principals,
fixed-release sharing and revocation. After the browser and HTTP server close,
independent SQL checks verify the dataset head, four releases and acceptance
receipts, frozen selection and revoked grant. Screenshots, redacted logs, source
hashes and results go under `target/console-backend.*/`. The test schema, temporary
storage and container are removed when the run ends. CI runs this as the separate
`console-backend` job and retains failure artifacts. This evidence does not supply
a VoiceOver result or a representative-data qualification.

For the real screen-reader gate, run `node tests/console/serve.js` and open
`http://127.0.0.1:8093/?demo` in Safari. It serves only synthetic demo assets on
loopback. Use Cmd-F5 to enable VoiceOver; VO means Ctrl-Option. VO-Right/Left reads
items, Tab moves among controls, VO-U opens the rotor, and VO-Space activates.
The [original script](evidence/console/original/voiceover-script.md) is retained;
the following tasks consolidate the reviewer's five workflows and add conflict
recovery. Record both desktop (1440×900) and narrow (390×844, where supported)
results. A resized desktop browser is not an iOS VoiceOver qualification.

1. **Inspect admission and metadata.** Submit an empty token and confirm the error
   is announced; then use any demo token and the prefilled dataset. Read the
   Admitted status, admission policy and Assessment findings. Open the file tree
   to `sub-04/func/sub-04_task-rest_bold.nii.gz`: effective TR is 1.5 s and header
   spacing is 2 s. Check folder expanded/collapsed state, labels/units, inspector
   navigation, and filter focus/text while resizing.
2. **Correct the mismatch.** Choose Add sidecar and change the path to
   `sub-04/sub-04_task-rest_bold.json`. Set `{"RepetitionTime":2.0}`. Submit once
   without a reason to check error announcement/focus, then give a reason.
   Review the one affected file, the resolved mismatch, diff and content table;
   accept, open the new release and verify TR 2 s with the subject-level origin.
   Cell labels should not be spoken twice. In a fresh demo, try a negative TR
   and confirm blocking findings are encountered first and acceptance is disabled.
3. **Inspect fieldmaps.** Use Relationships to identify the AP fieldmap correcting
   a run; inspect the PA fieldmap and its unused status. Confirm link direction,
   endpoint names and disclosures can be understood without sight.
4. **Freeze a query.** In Query, set participant age at least `30`, freeze the
   selection, read its fixed release and result paths. Check numeric input labels,
   readable result tables and focus after navigation.
5. **Grant and revoke.** In Access, use synthetic recipient
   `6c1d8e2a-4b7f-4a90-b3e5-1f2d7c9a0e64`, fixed-release scope and both read actions.
   Create a grant; confirm announcement and focus on its Revoke control. Start
   revoking, cancel with Escape, then confirm a second attempt. Confirm the result
   is announced and actions are read in form order.
6. **Recover a concurrent correction.** Open
   `http://127.0.0.1:8093/conflict.html`. This test-only fixture prepares an EchoTime
   0.031 draft after a synthetic colleague accepts FlipAngle 60. Follow Correct
   the current head using VoiceOver. Read the changed-file warning and Your
   earlier edit disclosure. Current content must retain FlipAngle 60 and EchoTime
   0.03. Reapply EchoTime 0.031, preview and accept; check both values survive.

Report tester, date, OS/Safari/VoiceOver versions, viewport method and dimensions,
the source hashes printed by the fixture server, and pass/fail/not-run for each
task at each layout. Include exact spoken wording for missing/duplicated labels,
focus loss, unannounced errors or confusing outcomes. Any unsupported narrow
layout remains not run. Record the outcome in the console adoption receipt;
automated axe/keyboard results do not supply this human result.

## Credential and authority contract

An operator issues a random 256-bit opaque credential for an existing principal, with a label and a lifetime from one second to 30 days. PostgreSQL retains only its SHA-256 digest, principal, expiry, revocation state and audit events. SHA-256 is suitable here because the input is a generated high-entropy secret, not a human password. Several credentials can authenticate the same stable principal; rotating them changes neither attribution nor custody.

Send exactly one `Authorization: Bearer <token>` header. The server ignores caller-supplied principal headers and accepts no cookie or query-string authentication. A credential authenticates the principal's **current** authority; this initial profile is not an attenuated worker credential. Grant issuance, acceptance and file reads still pass through the catalog.

The PostgreSQL adapter rechecks credential liveness inside each authorized transaction and locks the credential against concurrent revocation. A request that authenticated before revocation cannot later publish with that stale context. Revocation and an already running catalog transaction serialize at that lock. Expiry is checked at authorization boundaries, rather than retroactively cancelling a transaction.

File delivery checks current grants and credential state before every 64 KiB chunk. Revocation stops subsequent application chunks; bytes already delivered or buffered in the transport cannot be recalled. Late revocation or integrity failure aborts a download, so clients must require successful transport completion and verify the returned SHA-256 and size. Downloads do not use conditional cache responses.

The supported recovery flow invalidates **all restored API credentials** before opening the recovery gate. Operators issue fresh credentials after reconciling current authority. This deliberately avoids resurrecting a token whose revocation happened after the backup.

## Start and provision

Set the PostgreSQL variables documented in the [README](../README.md#run-the-postgresql-slice), plus:

```sh
export SANCTITY_STORAGE=/absolute/path/managed-storage
export SANCTITY_HTTP_PORT=8080
```

Choose stable UUIDs for the curator principal and institutional custodian. With local operator database access:

```sh
sbt 'app/runMain sanctity.app.RepositoryCli create PRINCIPAL_UUID CUSTODIAN_UUID'
sbt 'app/runMain sanctity.app.RepositoryCli import PRINCIPAL_UUID DATASET_UUID /absolute/path/bids /absolute/path/managed-storage import-001'
sbt 'app/runMain sanctity.app.RepositoryCli issue-credential PRINCIPAL_UUID 3600 curator-client /absolute/path/new-credential.json'
sbt 'app/runMain sanctity.app.ApiServer'
```

Replace the uppercase identifiers with the chosen UUIDs and the dataset ID printed by `create`. Import uses the durable inventory/checkpoint/lease workflow. Retry with the same source, dataset, principal and key to resume or retrieve its accepted receipt. The declared source inventory must remain available to this CLI's `begin` step, even though the underlying journal can reconcile an already accepted candidate without rereading source bytes.

Credential issuance creates a new file with POSIX mode `0600`; existing files are never overwritten. The file contains `credential`, `principal`, `expiresAt` and `token`. The token is never printed. Issuance fails for an unknown principal and confers no grants. A failed credential-file write revokes that newly issued credential.

The server binds only `127.0.0.1`. External deployment requires a TLS reverse proxy and an appropriately protected origin host. It does not interpret forwarded identities or implement OAuth/OIDC browser login. There are at most 64 connections and eight concurrent request-preparation operations; these are bounded starting settings, not measured production capacity.

To revoke a credential:

```sh
sbt 'app/runMain sanctity.app.RepositoryCli revoke-credential CREDENTIAL_UUID'
```

Dataset creation, local import, credential administration and recovery are trusted operator commands. They are not HTTP routes. Operators already hold database access; this CLI is not an additional authorization perimeter.

## HTTP workflows

Every `/v1` route requires authentication. There are no raw blob-digest, local-filesystem, arbitrary-manifest, arbitrary-activity or arbitrary-interpretation endpoints.

| Method and path | Result |
| --- | --- |
| `GET /v1/me` | Authenticated stable principal |
| `GET /v1/datasets/{dataset}` | Current head; requires dataset-wide metadata authority |
| `GET /v1/datasets/{dataset}/releases/{release}` | Pinned public inventory, interpretation identity and assessment |
| `GET /v1/datasets/{dataset}/releases/{release}/metadata?path=...` | Effective values, origins and override history |
| `GET /v1/datasets/{dataset}/releases/{release}/file?path=...` | Authorized chunked bytes, `X-Sanctity-SHA256` and `X-Sanctity-Size` |
| `POST /v1/datasets/{dataset}/releases/{release}/corrections` | A prepared candidate plus content and metadata impact |
| `GET /v1/candidates/{candidate}` | Preparation identity and assessment |
| `POST /v1/candidates/{candidate}/accept` | Durable acceptance receipt; requires `Idempotency-Key` |
| `POST /v1/datasets/{dataset}/grants` | Curator-controlled grant to a principal |
| `DELETE /v1/datasets/{dataset}/grants/{grant}` | Revoke a grant |
| `POST /v1/selections` | Persist a correlated query over pinned releases |
| `GET /v1/selections/{selection}?offset=0&limit=100` | Reauthorized, stable membership page |

Metadata inspection requires `ReadMetadata`; file delivery requires both `ReadMetadata` and `ReadContent` in this profile. Fixed-release recipients can inspect their authorized release without learning or accessing the current dataset head. The public release representation omits source labels, private evidence references, retained converter files/logs and internal activity records. Curated BIDS paths and effective metadata are still part of the recipient's approved data.

A correction request has exactly these fields:

```json
{
  "path": "task-rest_bold.json",
  "text": "{\"TaskName\":\"rest\",\"RepetitionTime\":1.8}",
  "reason": "Correct the acquisition timing"
}
```

It produces a durable draft and candidate. Inspect `candidate.assessment`, `metadataChanges` and `relationshipChanges` (including changed direct dependency paths), then accept using the candidate ID and a stable idempotency key. Retrying acceptance returns the same receipt. Retrying correction preparation creates another draft; preparation is not claimed to be idempotent.

A fixed-release read grant:

```json
{
  "recipient": "RECIPIENT_UUID",
  "release": "RELEASE_UUID",
  "actions": ["ReadMetadata", "ReadContent"]
}
```

A null or omitted `release` explicitly covers the dataset's future releases. Fixed-release grants permit reading only. Delegation and `ManageAccess` grants are unsupported.

A query preserving same-session correlation:

```json
{
  "releases": [{"dataset": "DATASET_UUID", "release": "RELEASE_UUID"}],
  "participants": {
    "op": "hasSession",
    "conditions": [
      {"op": "suffix", "value": "T1w"},
      {"op": "all", "conditions": [
        {"op": "suffix", "value": "bold"},
        {"op": "entity", "name": "task", "value": "rest"}
      ]}
    ]
  },
  "artifacts": {"op": "suffix", "value": "bold"}
}
```

Save the selection ID to page the resolved membership. Every retrieval checks all contributing release grants again. Subsequent releases do not change its membership; revocation denies retrieval rather than silently shrinking the result.

## Limits and failures

JSON requests must use uncompressed `application/json`, valid UTF-8, unique keys and recognized fields. Limits are 4 MiB, nesting depth 32, 20,000 JSON nodes and 15 seconds to receive the body. Query limits remain 256 nodes, depth 16, 1–100 pinned releases and pages of 1–1,000 rows. Numeric query values use bounded plain decimal notation; exponent notation is rejected by this profile.

Responses use `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`. Errors have a stable `{"error":"code"}` envelope; operational exception messages, paths and credentials are not reflected.

| Status | Meaning |
| --- | --- |
| 400 | Invalid or unsupported request |
| 401 | Missing, invalid, expired or revoked credential; Bearer challenge included |
| 404 | Absent or unauthorized resource |
| 408 | Body reception timeout |
| 409 | Concurrent-state or idempotency conflict |
| 413 | Request or query limit exceeded |
| 422 | Candidate assessment prevents acceptance |
| 500 | Repository operation or integrity failure |
| 503 | Authentication unavailable or recovery gate closed |

An authenticated mutation may return 404 if its credential is revoked between authentication and the transaction. A stream failure occurs after headers have been sent and cannot become a JSON error response.

## Verification and remaining work

`scripts/check-postgres.sh` runs the HTTP tests against disposable PostgreSQL, including a real Ember server/client, correction and retry, exact bytes, fixed-release sharing, frozen selections, invalid requests, stale authenticated contexts, chunk-boundary revocation and recovery credential invalidation. The ordinary suite checks the strict JSON and query grammar.

The current engineering priority is the [scientific core](scientific-core.md). A later identity adapter will need: verified issuer/subject bindings, account lifecycle and membership, authentication assurance and recovery reconciliation. General uploads, remote view preparation, full-dataset archive delivery, browser sessions, audit/metrics endpoints, deployment TLS/rate limits and large-response pagination are not part of this HTTP profile.

Protocol reference: [RFC 6750 bearer-token usage](https://www.rfc-editor.org/rfc/rfc6750). The adapter uses the [http4s 0.23 DSL](https://http4s.org/v0.23/docs/dsl.html); the application pins 0.23.30.

Release relationship evidence distinguishes JSON field declarations from companion-selection rules. Companion evidence includes the selected path, matching entities and applicable hierarchy; `evidenceVersion` identifies the encoding. Companion writes currently use the application API only.

Candidate and release assessments include `checks`: each exposes a versioned rule, target, exact input roles/paths/digests/sizes, observed facts and findings. DWI checks expose gradient counts, header facts and numeric policy. These observations are separate from authored/effective metadata; empty historical checks mean no such recorded assessment. Companion corrections still use the application API.

The event profile uses the same assessment `checks` representation: table/image/metadata inputs, event and missing-value counts, effective timing fields with origins, and regular/variable/unavailable timing context. `EventsOutsideWindow` and unavailable-context findings are warnings; table syntax/value errors and inconsistent supplied timing block admission. Companion edits continue through the application API.

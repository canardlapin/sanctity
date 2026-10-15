# Organization reference model

F02.1 implements ADR 001–003's organization records and atomic reference transitions.
F02.2 persists that contract in PostgreSQL V008 through the same decision function.
F03.2 connects the shared authority model to PostgreSQL V009 transactions: project roles,
explicit stewardship and exact independent/derived grants use current eligibility and
expiry. Historical curator attribution supplies no access bypass after bootstrap.

`Organization.scala` defines typed IDs; checked Unicode display text and generations;
principals, labs, projects, memberships, participations, placement, explicit stewardship
and navigation-link records. Display names are not identifiers. Membership-derived
participations name the exact membership ID; ended IDs are never reused.

`ReferenceOrganizationCatalog` accepts an explicit trusted principal/dataset inventory
and installation-operator set. It supports lab/project creation and naming, membership
and participation appointment/ending, initial placement of a registered empty dataset,
explicit stewardship appointment/ending, confirmed role inheritance, archival/unarchive
and emergency principal disablement. Initial placement creates explicit creator
stewardship and defaults to `ExplicitOnly`.

Each mutation checks the current actor and all required record generations in one
`Ref.modify`, commits its audit and idempotency result atomically, and leaves state
unchanged on failure. A retry reauthorizes the actor before returning its original
receipt; a reused key with different intent conflicts. Project generations also track
participation and membership-derived authority changes. Role-inheritance confirmation
compares both generation and current eligible recipients/roles, including expiry.

Ordinary transitions cannot remove the last eligible lab admin, project steward or
explicit dataset steward. Emergency disablement overrides those appointments while
retaining attribution; `recoveryNeeds(now)` identifies affected resources, including
natural expiry. No expiry is extended to avoid a recovery need. Lab administration
never implies project stewardship, and project stewardship never implies explicit
dataset stewardship. No principal, lab, project, placement or appointment is deleted.

Archival refuses active work. The reference adapter records distinct lease tokens,
so an old worker's release cannot clear another worker's fence. New work cannot be
registered on an archived project. Archive preserves appointments and separately
checked reads; new participation, placement and inheritance changes are refused.
A lab can archive only after its projects, and an archived parent must reopen first.

The snapshot/lease adapter methods are trusted in-process support, not public endpoints.
They deliberately expose complete state to contract tests and future storage adapters;
F03/F05 must filter every public read. The reference implementation scans maps and is
not evidence of bounded institutional-scale database work.

## Pure authority model and transaction handoff

F03.1 adds `OrganizationAuthority` in the kernel. It evaluates current principals,
explicit admission/guest eligibility, memberships and their lineage, project roles,
placement, explicit stewardship, grants and resource lifecycle at a supplied clock.
It consumes the existing organization record types without creating another principal
registry. Its state and `PolicyAdmission` are trusted adapter inputs, never browser
claims or a substitute for authenticating and rechecking a credential/session.

The new `Permission` type includes explicit `Discover` and `ReadEvidence`. The existing
`Action`/`Grant` wire contract remains unchanged; its explicit adapter preserves every
legacy action and exact release without adding discovery, evidence or an expiry.
F03.2 persists the expanded permission/basis model through `grantPolicy` and uses this
same decision function under the database policy guard. Public disclosure APIs remain F03.3.

Project roles authorize only primary `ProjectMembers` datasets; links never grant
rights. Lab administrators manage their organization without acquiring dataset access.
Explicit dataset stewards additionally hold restricted evidence and stewardship
authority. Grants enumerate actions, cannot delegate `ManageAccess`, and require the
issuer to hold each action when issued. Independent shares survive issuer departure
and unrelated offboarding. Derived shares require every original, applicable named
appointment; a new membership ID cannot revive the old grant.

Expiry is exclusive at its exact instant. Guest approval, verified binding and current
sponsorship gate every path; guest roles/grants require finite expiry within sponsorship.
Principal disablement and unavailable admission deny every action. Archived projects
retain authorized reads and grant revocation while refusing new writes/grants. Dataset
and release quarantine/withdrawal deny reads, including a protected current head.
Receipt access, cached chunks, selections, provenance, candidate evidence and processing
input/output admission compose these same current scope/action decisions.

Directory/share/link projections return only authorized identifiers and exact scopes;
they do not infer private parent names or current heads. Self explanations require the
requested permission. Inspecting someone else's basis also requires current dataset
management and the requested right; lab administration alone is insufficient. Explanations
separate stewardship, role and share bases and report the earliest applicable expiry,
including membership lineage, without exposing other recipients, issuers or global
policy revisions. Missing/hidden resources and unauthorized explanations share the same
denial. These projections are reference semantics, not a qualified database query plan.

The [F03.1 receipt](evidence/institutional-pilot/F03.1/receipt.json) records execution of
the unchanged 38-case ADR corpus, role/action checks, property tests and the organization
regression suite. The [F03.2 receipt](evidence/institutional-pilot/F03.2/receipt.json)
separately records the guarded SQL matrix, transaction/HTTP regression and package
checks. Authenticated browser identity, complete disclosure surfaces, continuous
stream/heartbeat checks and independent recovery remain subsequent tasks.

## PostgreSQL cutover and current boundary

V008 extends the existing principal registry and adds labs, projects, memberships,
participation, placement, stewardship and navigation links with generated query keys,
foreign keys, generation/status constraints and lookup indexes. Scientific tables retain
original IDs and attribution; grants retain exact actions, scope and revocation.
Nullable issuer/expiry and explicit independent provenance prepare subsequent policy work.

Schema migration does not silently assign an operator. Stop old servers and workers,
then generate a private preview with `migrate --organization-preview OPERATOR NEW_FILE`.
Review the mapping and pass its printed digest to `--organization-bootstrap OPERATOR DIGEST`.
The inventory digest binds the legacy state. Changed input refuses cutover; the reviewed
mapping, genesis event and initialized flag commit in one transaction. Exact retries
return the stored report. A dedicated migration lab has the nominated operator as admin;
projects group legacy custody/curator pairs. Each dataset is `ExplicitOnly` and gets an
explicit legacy-curator steward. Lab administration grants no dataset read rights.
No identity verification is inferred from these imported principal identifiers.

The server requires completed bootstrap. Uninitialized library mode exists only for
legacy maintenance/fixture staging. Legacy `repository create` is refused after bootstrap;
the trusted `PostgresOrganizationCatalog.createDataset` atomically creates the scientific
record, private placement, explicit stewardship and event/receipt. Public creation and
administration commands remain F02.3. Organization mutations currently require the
operator database connection as well as current application authority. Runtime receives
read/lock privileges and append-only grant-event/head updates, not bootstrap or
organization-administration writes. Row security excludes operator event kinds.

Organization changes exclusively lock one policy row; scientific authorization, import
lease acquisition and publication share it before taking dataset/credential locks.
Transitions use database time and the reference decision function. Event, record changes,
generations and exact retry receipt commit together. Archival checks actual live import
leases and nonterminal processing runs; archived projects remain readable under current
rights and refuse new writes. Disablement invalidates credentials/import leases in that
same transaction and denies cached principal contexts without changing attribution.

Events reserve installation/epoch/hash-chain and certificate fields, with an explicit
`LocalOnly` durability state. Genesis records imported principals, mappings, grants and
credential lifecycle metadata without bearer secrets. This is **not** an independently
durable policy journal. F03.2 adds current grant/credential/invalidation coverage through
one transaction boundary; [the mutation inventory](policy-transactions.md) names its
scope, local event versions and future identity/lifecycle extensions. O03.6 must implement [ADR004](decisions/004-policy-durability.md)'s current-capability
witness protocol; O03.2 retains full identity/session integration.
Legacy snapshot capture/reconcile refuses organization-enabled catalogs; it cannot reopen
a restore while omitting the new authority records. Source-independent recovery remains open.

This first adapter loads complete organization state and retry history for decisions and
hashes the full legacy inventory for preview. Query indexes do not establish bounded
institutional work; H04 remains open. V009 now enforces `ProjectMembers` inheritance,
original appointment dependencies, principal/guest eligibility and current expiry through
`OrganizationAuthority`. Navigation links convey no rights. Eligibility/lifecycle columns
are trusted read-model inputs; public guest and lifecycle administration remain F04/O04.

Remaining integration has explicit owners:
- **F02.3:** governed application/API commands, complete placement/move approvals,
  atomic stewardship-transfer commands, bootstrap/recovery and public error mapping.
- **F03.3/F03.4:** complete public disclosure/effective-access reads, restricted evidence,
  streams and processing admission. F03.2 supplies transaction authority, credential
  rechecking, protected receipt retries and current policy-event ordering.
- **F04:** verified login identities and guest sponsorship/expiry; a reference fixture's
  local principal is not evidence of a verified institutional person.
- **F05/W02:** filtered navigation links and release-bound work/notes; links confer no rights.

The [F02.1 receipt](evidence/institutional-pilot/F02.1/receipt.json) retains the tested
state, concurrency, expiry, denial and historical-identity invariants.

The [F02.2 receipt](evidence/institutional-pilot/F02.2/receipt.json) records the database,
package and retained historical-corpus qualification for this migration.

The [F03.2 receipt](evidence/institutional-pilot/F03.2/receipt.json) records V009 authority
and local event enforcement; [the transaction handoff](policy-transactions.md) defines
the O03.6 integration boundary. Organization-aware restore is still unavailable.

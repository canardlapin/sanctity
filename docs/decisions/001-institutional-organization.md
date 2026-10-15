# ADR 001: Institutional organization without changing scientific identity

14 October 2026. Version 1. Engineering implementation baseline for F01.1 and
F02–F06, based on the [product vision](../vision.md). This specifies future
behavior; it does not assert that organizational storage or APIs exist today.
Institution-specific ownership, naming and identity-provider settings remain SITE01
decisions. No institutional approval is claimed.

## Decision

Keep a small, mutable organization catalog beside the immutable scientific catalog.
An installation serves one institution with several labs and approved guests.
People have stable principal IDs independent of their login identities. A lab groups
people and administrative responsibilities. A project organizes datasets and work;
its participants need not belong to its owning lab. A dataset has exactly one primary
project and independently identified custody. Optional project links are navigation
relationships and confer no access.

Neither project placement nor changing a display name creates a scientific release.
Dataset, release, artifact, manifest, interpretation and acceptance identities retain
their existing meanings. Historical actor and custodian references remain resolvable.
Participant labels remain dataset-scoped; `sub-01` in two studies denotes no common
person without an explicitly governed mapping outside this tranche.

## Resources and state

All new IDs use typed opaque identifiers consistent with the kernel. IDs are never
derived from names, email, BIDS labels or paths and never reused. Mutable records
have a monotonically increasing generation, creation time and attributable audit.
Names are nonempty, bounded display strings, not authority. Initial limits: names
200 Unicode code points, descriptions 4,000; reject control characters, render as
text and accept duplicate names (show authorized context to disambiguate).

| Resource | Required fields and states | Meaning |
| --- | --- | --- |
| Principal | ID, display label, active/disabled, generation | Durable attribution; login bindings are separate verified records |
| Lab | ID, name, description, active/archived, generation | Owning administrative context, not an automatic data-access group |
| LabMembership | ID, lab, principal, member/admin, active/ended, expiry, generation | Named membership, separate from project participation |
| Project | ID, owning lab, name, description, active/archived, generation | One owning lab; cross-lab participation is explicit |
| ProjectParticipation | ID, project, principal, reader/contributor/steward, active/ended, expiry, optional sponsoring membership, generation | Current role with recorded independent or membership-derived basis |
| DatasetPlacement | dataset ID, primary project, `ExplicitOnly`/`ProjectMembers`, generation | Exactly one primary project; explicit access policy, independent of scientific head |
| ProjectDatasetLink | ID, project, dataset ID, generation | Optional pointer, visible only when both ends are discoverable |
| DatasetStewardship | ID, dataset, principal, active/ended, generation | Explicit administrative authority and scientific responsibility |
| Dataset / Release | Existing scientific fields and identities | Custodian and historical curator/acceptor attribution are retained |

Do not create a generic organization hierarchy or policy language. Restricted source
evidence is a distinct permission scope even on a project-inheriting dataset.
Project membership never opens that evidence automatically. Project/dataset
stewardship is not synonymous with custodianship, institutional operator access or
being an author of old data.

## Creation, archival and reassignment

All state changes authorize the current actor and check expected generations within
the same transaction as the records, audit and idempotency result. No endpoint accepts
a principal supplied by the browser as its authentication context. New resource
creation requires a scoped idempotency key; updates require that plus expected
generations. An identical retry returns the existing result only after current
authorization. A different payload with the same key, or a stale generation, conflicts.

| Transition | Preconditions | Result / rejected case |
| --- | --- | --- |
| Create lab | Trusted installation operator appoints an active initial admin | Lab plus admin atomically; no data-read grant |
| Create project | Active lab admin in active lab; active initial project steward | Project plus explicit steward participation; admin does not inherit stewardship |
| Add participant | Active project steward; verified active principal; sponsorship/expiry recorded where required | Role explicitly selected; being in another lab is neither sufficient nor disqualifying |
| Create dataset | Contributor or steward in active primary project, recognized custodian | Empty scientific dataset plus placement and explicit creator stewardship atomically; default `ExplicitOnly` |
| Enable `ProjectMembers` | Explicit dataset steward, project active, expected policy generation, access-impact preview | Project role actions apply; preview names recipients/actions and future-release scope |
| Rename lab/project | Lab admin / project steward respectively, expected record generation | Only organization metadata changes; hidden names remain hidden |
| Archive project | Project steward, expected project and policy generations; no active intake/publication leases | Reads remain subject to current access; block new dataset/draft/import/run/grant creation and acceptance; allow revocation, cancellation and governed unarchive |
| Archive lab | Lab admin; every owned project already archived; preserve an active admin | Block new projects/memberships; retained authorized reads and revocations continue |
| Unarchive | Current lab admin / project steward; parent lab active for project | Resume writes after current-policy checks; no automatic renewal of expired authority |
| Move dataset | Explicit dataset steward plus source and destination project stewards; both projects active; expected placement and both project-policy generations | Atomic placement and audited access-impact decision; optional old link only if explicitly requested |
| Change custody | Trusted operator records current custodian approval and receiving custodian acceptance, explicit reason and expected generation | Independent audited custody transfer; no implied change of placement, grants or historical records |

A move request freezes the proposed destination, policy (`ExplicitOnly` unless
explicitly confirmed otherwise), recipient/action differences and relevant generations.
Each required steward approves that same request. One principal can satisfy multiple
roles but every role must be checked. Commit rechecks approvals, active status and
generations. A concurrent membership/policy change invalidates the preview. Existing
explicit grants and dataset stewards remain unless the same authorized transaction
explicitly revokes/transfers them; the preview lists these survivors. Membership-based
rights tied to the old placement end, and new inheritance starts only with explicit
approval. Release/content IDs, retained bytes and historical custody do not change.
Project ownership moves between labs are deferred; create a destination project and
move datasets through this governed operation instead.

Archival is an administrative state, not withdrawal, deletion, credential revocation,
or a quality verdict. No hard deletion of attributed principals, labs, projects or
datasets is provided here. Withdrawal and erasure are separate O04 controls and take
precedence over archival read access. Recovery may replace an inactive last steward
using ADR 003's restricted operator procedure.

## Two-lab example

Lab `L-A` (Memory) and lab `L-B` (Imaging) belong to the same installation. Alice is
an admin of L-A and steward of project `P-memory`, owned by L-A. Bob, a researcher
from L-B, is explicitly a reader in P-memory. Eve is an approved external guest
with a finite sponsored project participation. Lab membership alone grants neither
Bob nor Eve access to P-memory.

Dataset `D-open` belongs to P-memory with `ProjectMembers` enabled and custodian
`C-institution`. Alice is its explicit dataset steward. Bob can read its accepted
releases `R1` and `R2`; a new R3 becomes readable under the same dataset policy.
Dataset `D-restricted`, in that same project with `ExplicitOnly`, is discoverable
only through an explicit qualifying grant or stewardship. Bob cannot see its name,
count, files, status or existence merely because he can see the project. Eve can
instead receive an independent read grant for exactly D-restricted/R1, expiring on
a stated date. Her shared-release entry does not reveal R2, the current head or the
project's private activity. Each dataset has its own `sub-01`; no identity merge occurs.

Project `P-methods`, owned by L-B, may link D-open. The link gives an authorized Bob
a route to the same dataset, but gives its otherwise unauthorized members nothing.
Moving D-open to P-methods changes placement and the explicitly approved access
policy, while R1/R2, all content hashes, acceptance receipts and C-institution remain
unchanged. A grant fixed to R1 stays fixed to R1. Renaming P-memory later does not
retroactively rewrite any scientific provenance.

## Public API contract to implement

Resource prefixes are proposed `/v1/labs`, `/v1/projects`, `/v1/principals` and
existing `/v1/datasets`. JSON uses stable IDs, bounded display fields and generation;
mutation bodies carry `expectedGeneration` and `idempotencyKey`. Cross-resource
changes name every expected policy generation. Missing preconditions produce 428,
invalid input 400, stale/conflicting intent 409, unauthenticated requests 401.
Unauthorized resource discovery uses the same 404 shape as absence. An authorized
reader denied a known action may receive 403 without extra restricted detail.

| Surface | Required behavior |
| --- | --- |
| Lab/project GET and bounded directories | Only authorized rows and counts; cursors bound to principal, query and policy generation |
| Lab membership and project participation collections | Governed create/end/role changes; sensitive rosters limited to admins/stewards; readers see authorized project contacts only |
| Project dataset directory and optional links | Filter each dataset, omit forbidden links; no hidden totals or placeholder rows |
| Dataset placement/access preview and move requests | Inspect recipient/action changes; approve exact request; atomic commit with all preconditions |
| Dataset stewardship and grants | Explicit governed appointments, bounded nondelegable grants, effective-access explanation restricted to authorized administrators |
| `/v1/me` and shared releases | Current actor and authorized entries; fixed-release recipient sees the exact share without current-head or parent-project disclosure |
| Trusted operator commands | Bootstrap labs, custody transfer and audited stewardship recovery; no public “become curator” endpoint |

Pagination must not silently reuse a stale authorization snapshot. Current filtering
applies to every page; if the policy generation changed, return an explicit restart
conflict without counts from the old policy. Existing scientific inventory pagination
continues to bind exact releases.

Excluded: nested organizations, billing, automatic cross-study identity matching,
general task scheduling, implicit cross-institution federation, custom role DSLs and
new numerical QA. Work queues, notes and review status are W02 resources attached to
these IDs and exact releases, with the same permission rules.

## Implementation and review

F02.1 adds pure records/transitions, F02.2 is the single database migration owner,
F02.3 exposes governed administration, and F03 enforces the action contract.
The [authority matrix](002-institutional-authority.md) and
[migration/offboarding contract](003-institutional-lifecycle.md) complete this ADR.
Review against current `Dataset`, `Release`, `AccessContext` and `Grant` confirms
these additions require no rewriting of immutable scientific identity. Those runtime
types do not yet enforce this new catalog: implementation and qualification remain
separate tasks, followed by independent institutional access review in Q02.1.

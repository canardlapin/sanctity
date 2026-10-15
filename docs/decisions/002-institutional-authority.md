# ADR 002: Current institutional authority

14 October 2026. Version 1. F01.2 engineering specification, extending
[ADR 001](001-institutional-organization.md). This is the contract for F03's pure
rules, transactions and transport checks, not a claim of present enforcement.
Machine-readable examples are in [institutional-authority-cases.json](institutional-authority-cases.json).

## Actions and roles

Keep the existing `ReadMetadata`, `ReadContent`, `Propose`, `Accept` and
`ManageAccess` distinctions. Add explicit `Discover` and `ReadEvidence`; organization
administration is scoped to its own lab/project resource. A grant enumerates actions;
one action does not silently imply another. Role bundles below explicitly include
their prerequisites. Fixed-release grants remain read-only. Arbitrary path/participant
filters, delegable grants and a general policy language are out of scope.

For an existing role, “project dataset” below means a dataset whose **primary**
project is this one and whose policy is `ProjectMembers`. For `ExplicitOnly`, project
roles supply none of these dataset actions. Linking a dataset into a project is
insufficient. Project stewardship cannot turn on inheritance for a restricted dataset.

| Authority basis | Directory / metadata / bytes | Propose | Accept | Share / stewardship | Restricted source evidence |
| --- | --- | --- | --- | --- | --- |
| Lab member | Authorized lab's minimal identity; no dataset rights | No | No | No | No |
| Lab admin | Lab administration and roster; no automatic project/dataset read | No | No | Create project and appoint initial project steward; cannot replace existing dataset stewards | No |
| Project reader | Project identity and `Discover`, `ReadMetadata`, `ReadContent` on project datasets | No | No | No | No |
| Project contributor | Reader bundle | Yes on project datasets | No | No | No |
| Project steward | Contributor bundle; project administration | Yes on project datasets | Yes on project datasets | Nondelegable grants on project datasets; manage project participation; no implicit dataset-steward appointment | No |
| Explicit dataset steward | `Discover`, `ReadMetadata`, `ReadContent` on that dataset, all retained releases | Yes | Yes | `ManageAccess`, appoint/transfer explicit dataset stewardship, approve placement/policy changes | `ReadEvidence` on that dataset |
| Explicit dataset grant | Exactly enumerated actions; dataset scope includes future releases | Only if listed | Only if listed | Never `ManageAccess`; recipient cannot delegate | Only if explicitly listed and issuer has evidence authority |
| Explicit fixed-release grant | Exactly enumerated read actions on the named release; no current head | No | No | No | Optional explicit `ReadEvidence` only for evidence bound to that release |
| Approved external collaborator | Same actions as the explicit role/grant, gated by active verified identity and current guest sponsorship | According to role/grant | According to role/grant | No extra powers from affiliation/email | Explicit evidence authority only |
| Trusted installation operator | Administrative bootstrap, disablement and audited recovery, not ordinary data browsing | No automatic application right | No automatic application right | Recovery procedure in ADR 003 | No automatic application right |

An operator with direct database/blob access is operationally privileged. The table
describes application authority and does not pretend host administrators cannot read
their storage. Institutional operator access controls belong in SITE01/O01/Q02.

## Grant contract

Each grant records ID, issuer, recipient principal, dataset, optional exact release,
action set, creation time, optional expiry, independent/derived basis and generation.
The issuer must currently have `ManageAccess` plus every action being shared on the
same scope. `ManageAccess` cannot be granted. Steward appointment uses a dedicated
audited administration transition; possession of a data grant never permits it.
Accept does not imply Propose, and neither implies sharing. Usable UI bundles include
explicit discovery/metadata prerequisites, but low-level legacy grants retain exactly
their existing actions rather than silently gaining a role bundle.

An **independent** grant survives the recipient leaving a particular lab or project;
the confirmation screen must say so. A **derived** grant references named active
membership/participation IDs and requires all of them to remain active, unexpired and
applicable. IDs are never reused on rejoining, so rejoining does not resurrect an old
grant. A project role can itself be independent or require a specified lab membership.
The default for a new role is explicit independent participation; the admin UI must
show that lab offboarding alone will not end it. Ending project participation always
ends that role's dataset inheritance. Revoking the issuer's role does not retroactively
invalidate already issued independent grants; administrative offboarding must enumerate
and explicitly revoke them where intended. This preserves explainable stable shares.

For guests, active local guest approval and sponsorship are prerequisites on **all**
access paths, including an independent grant. Guest approval and guest participations/
grants require finite expiry; issuance cannot exceed sponsorship expiry. An expired
or ended sponsorship stops access even if a record survives. Email domains and project
links are not sponsorship. Verified provider bindings map to durable principals;
matching email strings alone cannot establish identity or authority.

At expiry (`now >= expiresAt`) deny. Full principal disablement overrides stewardship,
all memberships, all grants, credentials and sessions. Project archival removes write
permissions as in ADR 001 but retains currently authorized reads. Withdrawal/quarantine
O04 controls precede read authorization. Missing or indeterminate policy is denied.

## Decision order and disclosure

Evaluate against current durable state and an authenticated, active principal:

1. Validate credential/session/identity, principal state, guest approval and time.
2. Resolve the exact resource and current lifecycle policy without disclosing whether
   a forbidden resource exists. Reject withdrawn/unavailable scopes as applicable.
3. Collect applicable explicit stewardship, project role and grants. Check membership
   lineage, expiry, placement, inheritance mode and exact release constraints.
4. Require the requested action and any action-specific prerequisites. Multiple
   independent valid bases may authorize it; revoking one basis need not remove others.
5. Project the response to permitted resources/fields, and record the applicable policy
   generation for conflict/recheck. A stored result or digest is never a credential.

`Discover` exposes only a bounded safe dataset/share label and permitted resource
identifier. It does not expose metadata, byte counts or a current head. Listing the
current dataset head requires dataset-wide `ReadMetadata`; a fixed R1 grant cannot
observe R2 or whether R2 exists. The “shared with me” entry for a fixed grant carries
the explicitly shared label and exact R1, not its private project's name or activity.
No extra parent-project read authority is inferred. A metadata-only grant on a known
resource can read that exact metadata without acquiring directory discovery.

Project directory names and counts include only currently discoverable projects and
datasets. Lab admins may manage the roster and IDs of projects they govern, but get
no hidden dataset names/counts, activity, bytes or scientific metadata. Project rosters
are administered by project stewards; ordinary participants see only designated
project contacts, not a guest's private identity bindings. Counts, search suggestions,
recent activity and error messages all use the same filters. Hidden links are omitted.

Scientific explanations may contain references to restricted sources. Public metadata
may describe a supported result without identifying its restricted source. Raw source
paths, headers, inventory, logs and identifying conversion evidence require
`ReadEvidence` plus access to each referenced scope. A missing source permission must
produce a safe restricted indicator or a denied operation, never an implicit widening.
Discovery of the existence of source evidence is itself protected; a restricted
indicator is shown only where that existence is already authorized for disclosure.

## Historical and continuing work

| Surface | Required current decision |
| --- | --- |
| Release metadata or bytes | `ReadMetadata` or `ReadContent` for the exact dataset/release; historical authorship is not a grant |
| Candidate / proposed diff | `Propose` plus metadata rights on that dataset and permission for any disclosed source evidence; accepting additionally requires `Accept` at commit |
| Acceptance receipt / idempotent replay | Metadata access to its exact release; returning an old successful receipt reauthorizes today, without exposing later heads |
| Frozen selection / query / count | Reauthorize every contributing exact release and disclosed field; denied constituents block the claimed complete result rather than silently shrinking it |
| Delivery/cache resume | Current content permission on every source and exact representation; stored plan, cache key or receipt supplies no authority |
| Run preparation/execution | Current read rights to inputs, allowed recipe, explicit destination Propose authority and a bounded execution lease; reading alone does not confer destination authority |
| Run status/log/output inspection | Appropriate input/output and evidence permissions, including protection of command parameters and paths; run initiation is not perpetual access |
| Output deposit and acceptance | Recheck current destination Propose / Accept separately and lifecycle state; successful execution does not accept derivatives |
| Provenance link | The relation can be traversed only if both disclosed endpoints are authorized; a link alone grants nothing |

Every mutation checks policy in the same transaction that records its effect. F03.4
must reauthorize delivery at each admitted chunk (maximum 64 KiB), on every resume,
and before publishing results; an authorization failure terminates delivery instead
of reporting completion. Bytes already admitted into transport buffers before
revocation cannot be retrieved. No new chunk may be authorized using a stale cached
grant after the revocation transaction commits. Running external processes stop at
the next lease heartbeat (at most 5 seconds in the initial contract); output collection
and deposit always reauthorize independently. Resource limits and measured enforcement
tests remain implementation work, not a guarantee established by this document.

For a reader who also holds an independent R1 grant, ending project participation
removes current-head access but leaves the R1 share. Effective-access explanations
must list these distinct bases so an administrator can choose membership removal,
selected grant revocation, or full account disablement knowingly.

## Review and implementation use

The fixture format names preconditions, action/resource, expected allow/deny and
observable disclosure. It is a reviewed decision corpus, not an executable policy
test result. F03.1 must map it into pure-rule tests; F03.2/F03.3 must exercise the same
cases through PostgreSQL and HTTP, including concurrent revocation. Migration
compatibility and recovery ordering are defined in [ADR 003](003-institutional-lifecycle.md).

# Current authority and policy transactions

F03.2 joins the [reference rules](organization-reference.md) to PostgreSQL.
The contract is [ADR 004](decisions/004-policy-durability.md). This document
inventories the current implementation for O03.6; it does not qualify recovery.
[Catalog enrollment and acknowledgement](policy-catalog.md) describes the synchronous
witness adapter, protected trust configuration and operational retry behavior.

Every repository authorization takes the recovery gate, then the installation
policy guard, then credential/dataset locks. Reads and scientific transactions
hold the shared policy guard until commit. Organization, grant and credential
mutations hold its exclusive lock. They use the database clock and current
principal eligibility, appointment lineage, placement, stewardship, exact grant
scope/actions/expiry and lifecycle state. Curator attribution supplies no power
in an initialized organization. Explicit migration supplies stewardship instead.

`PolicySql.load` constructs the shared model from relational rows. It currently
loads the complete policy state; this is an engineering correctness baseline,
not a bounded-query or institutional-scale qualification. F03.3 owns the public
disclosure projections and F03.4 owns continuous stream/worker admission.
Existing repository actions, receipt retries and import renewals already pass
through the transaction guard. The SQL matrix qualifies the trusted state/decision
adapter, not every public projection. F03.3 reconciled the read surfaces: receipt replay
now requires metadata on its exact release (no `Accept`), and candidate retrieval
requires `Propose` plus dataset `ReadMetadata` (`Request.Candidate`), both keeping
private-source restrictions. Grant inspection and access explanations project
`datasetGrants` and `explain` under the shared guard; see the
[HTTP reference](http-api.md). An authorization admitted before a restrictive
transaction may finish first; subsequent admissions see the restriction.

## Supported mutation inventory

All initialized-catalog mutations below append `organization_event` and advance
`organization_policy` in the same transaction. Non-organization commands leave
its `request` column null, so the organization retry reader never decodes them
as organization commands. Policy events are not scientific outbox messages.

| Entry point / event kind | Policy effects and retained intent | Retry/admission boundary |
| --- | --- | --- |
| Organization bootstrap / `OrganizationGenesis` | Legacy principal and dataset attribution, exact grants including existing revocations, credential status without secrets, migration report, complete organization after-images | Explicit operator and preview digest; repeated bootstrap returns the same report |
| `CreateLab`, `RenameLab`, `CreateProject`, `RenameProject` | Changed lab/project records, initial admin membership or steward participation | Actor, request key, complete command, expected generations, before/after records |
| `AddMember`, `EndMember`, `AddParticipant`, `EndParticipant` | Appointment identity, named membership basis, role/status/expiry, generations | Current operator/organization admission; original appointment IDs remain ended on rejoining |
| `PlaceNewDataset` | Dataset identity/custodian/attributed curator, initial placement and explicit stewardship | Deterministic creation ID and payload-bound organization retry |
| `AddSteward`, `EndSteward`, `SetInheritance` | Stewardship or placement records; confirmed inheritance impact in request | Current command authority and expected generations |
| `ArchiveProject`, `ArchiveLab` | Current archive status and generations | Live-work fences; reads and restrictive grant revocation remain possible |
| `DisablePrincipal` | Principal before/after state, IDs of newly revoked credentials and timestamp, owned import lease IDs/tokens/expiry invalidated atomically | Cached API contexts are rechecked; later receipt, grant, acceptance and lease admission fail |
| `GrantIssued` | Exact grant issuer/recipient/scope/actions, creation/expiry, basis IDs, generation; complete new local principal record if the legacy grant API introduces one | Stable grant ID binds the entire intent; changed or revoked intent conflicts; current issuer and recipient eligibility rechecked |
| `GrantRevoked` | Grant before/after images, next generation and revocation time | Repeat revocation is a no-op only after current authority is rechecked; works on archived parents |
| `CredentialIssued` | Credential ID, principal, label, issuance/expiry; no bearer secret or token digest | Explicit database operator capability; retained caller ID after witness enrollment, one-time secret disclosure |
| `CredentialRevoked` | Credential ID/principal and revocation timestamp | Explicit operator; repeat revocation remains a no-op |

Credential operator events retain both the configured installation operator
identity and actual database login. These are local engineering operator
identities, not proof of institutional identity. API credentials authenticate;
they do not confer any dataset permission. A newly introduced local principal
is likewise not an approved institutional or guest identity.

Grant timestamps use PostgreSQL microsecond precision; finer input is refused rather
than silently changing a payload-bound retry. Expiry is checked against the current
database clock. It requires no timer
mutation/event and must never be reset during replay. Membership-derived grants
retain their original dependency IDs; independent grants survive the issuer's
later departure. Only exact historical actions survive migration: metadata
permission does not silently acquire discovery, content or evidence permission.

The V009 eligibility and lifecycle columns provide trusted read-model inputs
and fail closed on unknown encodings. Public guest sponsorship, identity binding,
browser session, quarantine/withdrawal/retention/hold administration are **not
implemented here**. F04/O04 must introduce governed commands, events and tests
through the same boundary before those capabilities can be enabled. SQL fixture
writes in the matrix tests do not constitute those administration features.
Legacy recovery invalidation remains confined to uninitialized catalogs;
organization recovery is still closed pending H03.1 and O03.7/O03.8.

## Local event and independent witness boundary

`PolicySql.commit` is the common transaction boundary. It captures installation,
writer epoch, event ID, prior head, proposed revision, actor, database actor,
command kind, machine reason, request identity/digest, exact changes and local receipt.
Expected generations and target IDs are retained in the organization request and
before/after records. Current APIs accept no separate human approval references;
the event records an empty approval list, never an inferred institutional approval. It computes
canonical sorted-key local JSON and a versioned digest chain, performs the
policy effects and appends the event before the catalog transaction commits.
An append failure rolls back the effects. Organization bootstrap/creation can
stage new rows within this same uncommitted transaction; no staged policy is
externally visible or acknowledged before the event commits.

Existing `organization-local-v1` events remain immutable. New events use
`policy-local-v2` and the `sanctity.policy-local.v2` digest domain, chaining from
the existing head. O03.6.2 recognizes both versions during enrollment and
retains their identities and exact historical bytes. Enrollment captures a
complete current checkpoint, including legacy defaults and restrictions; an
empty witness cannot legitimize an older catalog. The checkpoint includes existing
eligibility/lifecycle input columns. Unsupported nondefault state closes enrollment
instead of silently replacing it with `Local` or `Available`.

The local JSON representation remains distinct from the strict signed transport.
O03.6.1 supplies that transport and witness journal; O03.6.2 integrates it at this
boundary. V010 allows an explicit independent mode, atomic Pending events with
PREPARE certificates, and separate append-only COMMIT acknowledgements. The adapter
verifies PREPARE before local effects can commit, and verifies COMMIT before returning
completed success. Exact operation and payload resolution handles lost responses;
only the original live connection's proven rollback can produce ABORT.

Historical and unenrolled receipts remain `LocalOnly`. Enrolled organization receipts
expose `Pending` or `Durable`; pending and outcome-unknown failures have distinct
operation identifiers. Credential issuance in independent mode requires a retained
caller ID and never repeats an already-issued identity. See the
[operator guide](policy-catalog.md) for enrollment, fresh runtime identities, trust
configuration, admission deadlines and retry behavior. O03.6.3 completes the
[combined process-failure qualification](policy-process-qualification.md), and the
[O03.6 review](evidence/institutional-pilot/O03.6/receipt.json) passes all six original
criteria. H03.1 backup automation is next; replay and organization restore remain open.

## Runtime database boundary

The runtime can append grant events and advance the local policy head, but
cannot edit or delete historical events. Row security limits its inserts to
`GrantIssued`/`GrantRevoked`; operator organization/credential events require
the schema-owner/operator connection. Runtime grants do not include principal
updates, organization administration, credential issuance/revocation, lifecycle
changes, bootstrap fields, writer epoch, durability mode or recovery opening.
The runtime remains a trusted application service with grant-write capability;
this is not a claim of protection against a compromised service or schema owner.

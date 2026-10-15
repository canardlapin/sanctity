# ADR 003: Offboarding, migration and stewardship recovery

14 October 2026. Version 1. F01.3 engineering implementation baseline completing
[ADR 001](001-institutional-organization.md) and
[ADR 002](002-institutional-authority.md). F02.2 is the sole owner of organizational
schema/data migration. F03 implements enforcement; F04 adds verified identities and
sessions; O03 implements independent recovery durability. None is implemented by
writing this contract. Institutional recovery approvers remain a SITE01 input.

## Offboarding operations

Use separate, explicit commands with an impact preview showing surviving access bases:

| Operation | Authority / atomic effect | Surviving state |
| --- | --- | --- |
| End lab membership | Lab admin; end that exact membership and invalidate its derived roles/grants | Independent project roles and independent grants survive; other lab memberships survive |
| End project participation | Project steward; end that exact participation and its inherited/derived authority | Explicit dataset stewardship and independent grants survive; display them in the preview |
| Revoke grant | Current dataset `ManageAccess`; mark that grant ended and invalidate affected leases | Other valid grants or roles may still authorize access |
| End guest sponsorship | Authorized sponsor/project steward or installation identity administrator | Guest immediately fails the approval prerequisite on all access paths; retained records remain attributable |
| Disable principal | Trusted identity administrator/operator; disable actor, revoke sessions/credentials/leases and append policy event atomically | Historical authorship and records retained; no surviving application access |
| Transfer dataset stewardship | Current explicit dataset steward; add eligible active successor and end outgoing appointment atomically | Dataset/release/custody identities and independent grants unchanged unless explicitly included |
| Transfer project stewardship | Current project steward; add eligible active successor and end outgoing role atomically | Other roles, explicit dataset stewardship and grants remain separately governed |

Never implement broad account disablement as a loop deleting memberships. A single
current principal-state check must override all paths, including operator-issued API
credentials, historical receipts, cached delivery and processing leases. Identity
provider logout alone is not local disablement. Ending a sponsor does not implicitly
erase scientific attribution or history.

Reactivation is a governed identity-admin action requiring a review of surviving
memberships/grants and a policy event. It does not restore revoked credentials,
sessions, execution leases or ended guest sponsorship. The preview lists any
unexpired retained roles/grants that would become effective again; approval is for
that exact set and generation, with unwanted rights revoked in the same transaction.

## Last steward and emergency recovery

Ordinary removal/expiry changes must preserve at least one eligible active **explicit
dataset steward** per dataset and one eligible project steward per project. Project
inheritance is not a substitute for dataset stewardship. Scheduled expiry requires
a successor before it takes effect; a deadline still expires even when nobody arranged
one, leaving the resource restricted and needing recovery. Do not extend access merely
to satisfy a last-steward invariant. The same rule applies to a lab's last admin.

Emergency principal disablement always succeeds when authorized, including for a
last steward. Mark affected resources `needsStewardRecovery` in administrative state;
do not reopen access, appoint the lab admin implicitly or delete data. This marker
does not alter accepted releases. Other authorized reads may continue subject to
lifecycle policy; administrative changes requiring the missing steward remain blocked.

Recovery is a trusted local operator procedure with a case ID, reason, affected IDs,
expected generations, proposed successor and evidence of the institution's designated
custody/ownership approval. Until SITE01 names those approvers it can be rehearsed on
synthetic fixtures, but cannot be claimed as institutionally accepted. The operator
must identify itself with a separate attributable principal, not the old curator's
token. Approval is for that exact transfer, not a general operator read grant.

In one transaction: check recovery authority and approval reference, verify the
resource still lacks an eligible steward, recheck the successor is active/verified
and authorized to assume responsibility, append the new explicit appointment,
invalidate superseded administrative leases, increment policy generations and record
audit/outbox/idempotency receipt. Preserve the old curator/acceptor IDs as historical
attribution. Subsequent dataset access is performed as the successor with normal
checks. Existing independent shares are listed for separate review, not silently
revoked or reissued. Recovery of a sole lab admin follows the same restricted route.

## One migration authority

F02.2 owns an additive, checksummed PostgreSQL migration and a matching reference
catalog transform, reviewed together. F03/F04 consume those records; they must not
backfill competing principal/membership tables. Extend the existing V002 `principal`
table rather than creating another actor registry. Migration takes the existing schema
advisory lock, stops writes using the old authority implementation and runs under the
dedicated migration role established by O01.3. Do not support mixed old/new writers.

The migration input includes an explicitly named installation bootstrap operator
principal and an idempotent mapping inventory. It does not infer identity from email,
custodian strings or BIDS labels. Produce a preview/report before commit, including
orphan/malformed references, source counts and planned mapping IDs. Missing required
references or a partial previous migration are explicit failures; no fallback to
“everyone in the lab can read” is permitted.

| Existing record | New representation / preservation rule |
| --- | --- |
| Principal IDs in curator, grant, credential and historical actor fields | Stable local principal row for each ID; existing IDs/labels retained, no fabricated verified identity binding; active status cannot override credential revocation/expiry |
| Dataset ID, custodian and curator | Same scientific dataset; curator retained as historical legacy attribution and mapped to explicit dataset stewardship; custodian unchanged |
| Dataset placement absent | Place into an `ExplicitOnly` legacy project, deterministically mapped per existing `(custodian, curator)` pair; neutral display name, no scientific label inference |
| Legacy project ownership | One dedicated migration lab with the explicitly appointed bootstrap operator as lab admin; no dataset-read role for that operator; each legacy curator receives project stewardship for their mapped projects |
| Existing dataset-wide grant | Same ID, recipient, dataset and exact action set, independent basis; legacy non-expiring grants remain so pending explicit review, never silently extended to new actions |
| Existing fixed-release grant | Same ID and exact release/action set, independent basis; no head/discovery/source-evidence permission added |
| Existing credential | Preserve ID, principal, secret digest, issued/expiry time and revocation state; no plaintext reconstruction, secret reset or automatic guest classification |
| Releases, receipts, manifests, interpretation IDs, audit and outbox | Byte/identity-equivalent values; accepted-by and original curator references remain resolvable |
| New identity binding / guest approval | None inferred; F04 requires verified provider control or a governed explicit admission |

Legacy curators retain their previous dataset management powers through explicit
stewardship; **new policy no longer treats the historical `dataset.curator` field as
an unconditional bypass**. Keep it for compatibility and attribution until an explicit
schema/API evolution removes it, and route all runtime authorization through the
new policy records. Never rewrite it to make a transfer appear historically true.

Existing read grants do not gain the new `Discover` or `ReadEvidence` actions.
Authenticated direct-resource reads continue within their prior scope. A separate
audited access review may add safe directory discovery; migration itself does not.
New browser guests are not inferred from legacy token holders. Existing credentials
remain the existing credential boundary until F04 provides explicit binding/admission.
This preserves access without asserting that every historical token is an institutionally
verified human identity.

Example: dataset D7, custodian C2, curator P9, head R4 has a reader grant G3 to P8
for R2 and credential K8, revoked yesterday. Migration preserves and extends P9/P8's
principal records, creates a neutral legacy project for `(C2,P9)`, D7's `ExplicitOnly` placement,
and explicit D7 stewardship for P9. G3 remains exactly R2/read; K8 remains revoked.
R2/R4, manifests, all acceptance actor IDs and C2 compare equal before/after. The
bootstrap lab admin can administer the lab but cannot read D7. Another curator's
project is a separate record; no participant labels are merged between its datasets.

Verification must compare old/new grants action-for-action, credential states and
scientific identifiers/digests, then run the former permitted/denied reads plus ADR
002's cases. F02.2's report identifies exact input snapshot, migration version,
mapping IDs, counts and rejected rows. Replay under the same migration ID returns
the same mappings; it cannot create duplicate labs/projects/appointments.

If the transaction fails, no new-model writes are enabled. After committed authority
changes, do not boot an older binary that ignores disablement/new records. Restore
only into a closed service and reconcile current policy; a forward migration is the
normal repair. O01.4 must prove the supported upgrade/rollback boundary with the
actual packaged binaries and backup, not merely an empty database.

## Generations, concurrency and idempotency

Every administrative record has a generation. Each authorization-relevant change
also increments the affected principal and resource policy generations. A baseline
implementation may serialize organization policy changes through one installation
policy revision to avoid incomplete dependency invalidation; optimization must preserve
the same observable decisions. Project role changes invalidate dependent datasets,
and principal disablement invalidates all actor paths regardless of resource caches.

Mutations carry expected generations for the target and relevant authority context.
Use a transaction/lock protocol shared with acceptance, grant use, delivery admission
and lease renewal. Reading policy before an unrelated write transaction is insufficient.
Stale expected state gives 409; absent preconditions give 428. The conflict response
discloses current state only when the caller still has permission to read it.

An idempotency key is scoped to principal, operation and target; its payload digest
includes target IDs, action set, successor, expiry, expected generations and approval
references as applicable. Identical retry after success returns the same governed
receipt subject to current authorization, even if the resource generation advanced
because of that original success. A changed payload conflicts. Do not re-execute
side effects or disclose an old privileged receipt to a now-disabled actor.

Concurrency examples for implementation tests:

1. Bob prepares a correction using project contribution at policy revision 20. Alice
   ends his participation and commits revision 21. Bob's subsequent prepare/accept/
   retry must recheck current rights and fail; old prepared values are not authority.
   If Bob's authorized commit serialized first, its receipt remains an honest record
   of that earlier transaction. Revocation does not erase the accepted release.
2. Two stewards concurrently remove each other using generation 8. Only one eligible
   transition may commit; the second conflicts and cannot leave zero eligible stewards.
   An emergency disablement can still leave a recovery-needed resource, deliberately.
3. A dataset move previews inherited access at revision 30. A destination membership
   changes to revision 31 before approval. Reject the old move approval and require a
   fresh recipient/action preview; never reuse it for a different audience.
4. A steward transfer commits but its response is lost. The same key/payload resolves
   to its original receipt; an old outgoing steward may receive a denial/redacted
   confirmation if it no longer has authority. A replacement successor with the same
   key is a conflict, not another transfer.

## Independent policy journal contract

Each authority mutation records an ordered event with installation ID, unique event
ID, monotonic sequence/previous digest, schema version, actor, time, reason, affected
IDs, before/after generations, explicit changes, approval reference and idempotency
digest. No plaintext credentials are included. Principal disablement, credential/
session revocation, role/grant changes, expiry configuration, stewardship, custody,
placement and O04 lifecycle decisions all belong in this history.

The catalog transaction atomically changes policy and writes its local event/outbox.
O03 must replicate authenticated events and a trusted current checkpoint outside the
catalog/blob failure domain. Local commit is effective for serving immediately, but
the operator must see independent-durability acknowledgement separately; a pilot must
not report a revocation as recoverably durable before that acknowledgement. If an
operation is reported as pending replication, retry does not repeat the policy change.
O03.1 decides the transport, failure budget and checkpoint freshness within SITE01's
accepted recovery objectives; this ADR does not choose a cloud service prematurely.

On recovery, missing/gapped/unverifiable history or an unavailable trusted current
checkpoint keeps the service closed. A signed old checkpoint alone does not prove
currentness. Restore scientific state, replay verified current authority/lifecycle,
invalidate old sessions/credentials/leases according to the recovery contract and
only then expose data. Capture from a surviving original database is a useful current
tool, but does not satisfy independent disaster recovery.

Failure cases to retain: crash before catalog commit (no change); crash after commit
before replication (local denial effective, recovery remains closed without current
independent evidence); duplicate event (idempotent); out-of-order/gapped event (closed);
restored pre-offboarding backup (must not resurrect Bob's membership or K8); expired
last steward (access expires, audited recovery required). These are design obligations
for F03/O03, not completed disaster-recovery tests.

# Sanctity core hardening: delivery and acceptance plan

14 October 2026. Execution plan for the six core areas, before a qualified local
demonstration and later institutional pilot discussions.

**Meta-epic EP9: `bd-01M4X8M8Y18BD586KA2AD1BMH9`.** It coordinates existing work
through six area reviews, H01–H06, and one integrated-release review, H00. Existing
task IDs and parents remain intact. Eight new implementation tasks fill specific
gaps; 25 existing task specifications gain context, acceptance detail or corrected
dependencies. A subsequent recovery-priority amendment adds three foundation tasks
under O03, preserving the full integration requirements. Area reviews consume their
evidence rather than repeat implementation.

The [canonical backlog](institutional-readiness-backlog.json) contains every criterion,
owner role and blocking edge. The [mote ID map](evidence/institutional-readiness-motes.json)
identifies each item. The [pilot guide](institutional-pilot-epics.md) describes the
broader program; the [local demo contract](local-demo-plan.md) defines what we intend
to show. This plan governs engineering order and the evidence needed to say the core
is ready for that demonstration.

## Starting position and outcome

F01's organization/authority/migration contracts, O01.1's packaged Java service, and
L01.1's local-demo specification are complete. The retained 679-test run and packaged
smoke validate existing behavior. They do not implement memberships, independently
recoverable policy, capacity management or complete browser workflows.

The next outcome is one identified build that safely stores and organizes data,
enforces current access, completes ordinary repository work, stays within measured
resource limits, upgrades without rewriting history and recovers from the declared
failure model. That build must support the guided local demonstration and its docs.
An attractive happy path cannot close missing integrity, access or recovery work.

First installation remains the product owner's computer. We use synthetic/permitted
fixtures, a local test identity provider and isolated test services. No institution,
real-provider approval, human review or off-host backup medium is assumed.

## Six accountable areas

| Review | Core promise and responsible role | Existing work consumed | Observable exit |
| --- | --- | --- | --- |
| **H01: Governed catalog and current access** | Backend/identity lead | F02, F03, F04.1–F04.3, F05, F06 | Two labs and a guest use the real UI/API; migrated data retains identity; offboarding, expiry and concurrent stewardship changes preserve least disclosure at every surface. |
| **H02: Storage safety under failure** | Storage/backend lead | O02.3, O02.4, O02.6, plus H02.1 | Capacity reservations hold under competition; interrupted writes/publication do not produce false success; corruption can be found, quarantined and repaired from verified bytes. |
| **H03: Recovery and lifecycle** | Recovery/backend lead | O03.1, O03.2, O03.4–O03.8, O04.1, O04.3, plus H03.1–H03.2 | Restore without consulting original source state; current revocation/disablement/withdrawal survive; missing or stale policy keeps the service closed. |
| **H04: Bounded performance** | Performance/backend lead | W02.1, Q01.2, O02 resource controls, plus H04.1–H04.2 | Page size bounds underlying work; measured latency/memory/concurrency budgets hold during simultaneous reads, writes and slow downloads, with exact results and current permissions. |
| **H05: Everyday repository workflows** | Product/frontend lead | W01, W02, L01.4 | Users create, ingest, inspect, correct, accept, retrieve, share/revoke and manage release-bound review through supported screens, including retry/cancel/conflict paths. |
| **H06: Operation and upgrades** | Service/operations lead | O01, O02.1, O02.5, plus H06.1–H06.2 | Protected configuration and runtime roles; useful diagnostics/alerts; a populated old repository upgrades with historical meaning, bytes and access intact. |

Roles are accountable disciplines, not invented staff assignments. The actor who
claims a mote owns its implementation and receipt. Cross-area changes identify a
single schema/protocol owner before editing; F02.2 owns organization migration and
O03.1 owns the authority durability protocol.

Each area is currently open. Closing all six is necessary but insufficient: H00.1
must prove they coexist in the candidate release, and H00 reviews that result.

## Concrete gaps and amendments

### Authority must be current everywhere

The legacy [authority rule](../modules/kernel/src/main/scala/sanctity/kernel/Authority.scala)
allows a dataset curator every action. F02.2 removes that shortcut from the governed
service: explicit stewardship, current principal state and exact independent grants
now apply. F03 completes the inherited role/action/provenance matrix. F03.3 now explicitly tests restricted
source evidence and canaries in metadata, counts, links and errors. F03.4 tests the
ADR's admitted-chunk and execution-heartbeat bounds, including output deposit after
offboarding. Historical curator attribution never authorizes a disabled principal.

F03.2 now depends on O03.1: the transaction and policy-event/acknowledgment contract
must agree before authority persistence is implemented. This prevents recovery
history from being added later with incompatible semantics. The 38 ADR cases become
pure, PostgreSQL and HTTP/browser tests, not a second policy implementation.

### Storage needs accounting, repair and precise failure boundaries

O02.4 gains concurrent, durable, idempotent reservation tests across staging, exports
and backup pressure. Reclaiming abandoned staging must respect live leases and shared
references. Canonical-blob garbage collection remains disabled until separately
qualified; that does not excuse unlimited staging growth.

**H02.1** separates the bounded resumable integrity scrub and verified repair mechanism
from O02.6's qualification. H02.1 consumes H03.2’s reference inventory so repair and lifecycle do not build
conflicting reachability models. O02.6 gains a failure matrix around write, flush, blob
installation, catalog commit and response, including short writes, disk-full,
permissions, corrupted shared blobs and interrupted repair. Process termination
cannot be described as proof of physical power-loss durability.

### Recovery engineering must not wait for institutional media

**H03.1** implements manifest-backed backup automation against a separately permissioned
test store. **H03.2** implements reference accounting, a local retention/hold/expiry
profile and refusal of data requiring an unqualified erasure guarantee. O03.4/O03.5
and O04.3 consume these engineering results immediately. O03.3 and O04.2 still require
the institution's actual medium and lifecycle decisions before pilot acceptance.

O03.5 deliberately destroys or isolates the disposable original service/catalog/
storage namespace; restore cannot retain an unnoticed original mount, credential or
policy query. Backups and authenticated current-policy checkpoints live in separately
controlled services. The same computer still shares a physical failure domain:
this proves source independence, not survival of that computer or disk being lost.
Actual independent-medium and replacement-host proof remains O03.3/Q03.1 and is now
explicit in Q03.1's criteria. No existing physical recovery obligation is waived.

### Next core milestone: tested recovery for organization catalogs

The user-approved recovery priority addresses an actual functionality gap: the
organization migration correctly blocks legacy reconciliation, but its replacement
is not implemented. Keep that block until the new recovery path passes. An old
scientific backup must never restore a permission revoked after the backup.

Three priority-zero tasks extract the current-capability foundation from the later
complete integration work. Their full acceptance criteria are in mote and the
canonical backlog; all three remain open.

| Task | Mote | Required result |
| --- | --- | --- |
| O03.6 | `bd-01M4XPCN6RSRCS8CB96BR5Y8GY` | A real independent witness and transactional adapter cover every currently supported authority mutation, safely enroll existing restrictions and enforce ADR004 acknowledgment, continuity and writer fencing. |
| O03.7 | `bd-01M4XPDDP01BGZJJ73Z32TFCC1` | A packaged operator command restores H03.1 bundles with access closed, verifies current policy and scientific bytes, invalidates old credentials/leases and reopens through an audited, retry-safe barrier. |
| O03.8 | `bd-01M4XPDEAPFG0QFQT27TWEKH41` | A packaged backup → policy changes → original-source isolation → fresh restore rehearsal preserves exact retained science, denies every removed permission and permits legitimate freshly admitted access. |

**Execution order:** F03.1 → F03.2 → O03.6; H03.1 is independently ready.
O03.6 and H03.1 feed O03.7. O03.8 requires O03.7 plus F02.3's supported stewardship
administration and F03.3/F03.4's read/stream enforcement. Schedule this work before
browser workspace expansion, using existing tasks for those prerequisites. These
are explicit dependencies, not a blanket requirement to finish T2 before T3.

The rehearsal backs up first, then revokes a share and credential, removes a
membership, disables a principal and transfers stewardship. It independently checks
exact historical IDs/bytes/attribution, legitimate new-steward access, expired and
fixed-release grants, denied old tokens/leases and safe pending-work reacquisition.
Stale/truncated/tampered or unavailable policy evidence, unknown event kinds,
missing/corrupt blobs, unresolved preparations and a stale writer keep access closed.
Interruptions around barrier release and reopening must retry without partial access
or duplicate effects. A service that never reopens does not pass the positive cases.

The early profile explicitly lists its supported capabilities and rejects unsupported
versions/events. **O03.2, O03.4 and O03.5 retain every original acceptance criterion**
and consume O03.6, O03.7 and O03.8 respectively after identity/session integration.
They must prove guest sponsorship, identity binding, account recovery and browser
session invalidation on the same engine; a capability flag cannot waive that work.
H03 also retains withdrawal/lifecycle requirements. O03/H03/H00 and local-demo
acceptance stay open after the early milestone. O03.3/Q03.1 retain real off-host and
replacement-host qualification; separate services on one computer prove component
and source isolation only.

The [planning validation](evidence/core-recovery-planning/receipt.json) records the
new graph, preservation of original criteria and exact pre-amendment documents.
No recovery implementation or new test pass is claimed by this amendment.

### Performance needs a repeatable budget and correctness oracle

**H04.1** freezes quick and stress corpus specifications, expected query/access/export
answers and numeric budgets before performance qualification. The profile must name
CPU/memory/database/storage configuration, dataset/file/history cardinality, concurrent
roles, request mix, cold/warm state and sampling procedure. It must set p95 latency,
memory, connections, queues, active streams, reserve capacity and cancellation bounds.
Those numbers are engineering acceptance targets to establish, not achieved SLAs.

Q01.2 becomes the reusable harness and depends on H04.1 instead of SITE01.3.
**H04.2** measures and fixes mandatory-budget failures using that same profile and
independent correctness oracle. Q01.3 still evaluates the institution's permitted
corpus and accepted targets later. Measurements must include authorization, decoding,
serialization and database work, not just SQL timing or output page size.

W02.1 now explicitly covers stored frozen selections as well as inventories/history.
O02.3 holds permits for the complete streamed body: the current server semaphore
surrounds response construction and is not sufficient evidence for download limits.
Test skewed and hidden data, expired cursors, slow clients and concurrent publication.
Keep the current 10,000-entry intake limit until a measured implementation change
justifies it; rejecting unsupported work is preferable to quietly splitting a study.

### Workflow completeness includes failure and disclosure

Existing W01/W02 tasks remain the implementation owners. W01.5 gains precise durable
delivery identity, authenticated resume/cache handling and staging expiry. W02.2
adds explicit tests for disclosure through task titles, notes, attachments and activity
after a move or offboarding. L01.4 demonstrates the complete ordinary story; H05
reviews it with the operation and release-bound review evidence.

### Upgrades need old state preserved before migrations land

**H06.2** retains the old artifact and a synthetic catalog/blob corpus outside disposable
build output, with independent expected release/interpretation IDs, export hashes,
grant/credential decisions and pending-operation outcomes. F02.2 now depends on that
baseline; O01.4 consumes it for actual candidate upgrades. A migration cannot generate
its own expected answers from the already-upgraded state. H06.2 establishes the old
baseline and executable harness; F02.2/O01.4/H00 run candidate-specific comparisons.

**H06.1** implements metrics and delivers real alerts to a loopback test receiver,
including receiver failure/retry, recovery notices and secret canaries. O02.6 can use
this immediately. O02.2 still requires the institution's real routing and acknowledgment.
The H06 review requires metrics from all implemented producers, including backup and
policy-journal lag; an early missing producer is not a passed final metric. H06
therefore also consumes H02/H03’s storage and recovery reviews.

O01.2/O01.3 gain storage/origin/configuration canaries and negative database-privilege
tests across both service and operator entry points. O01.4 requires executed release
jobs. A real local CI runner may supply local execution evidence; a workflow file or
manual command must not be reported as a hosted CI run. Hosted execution remains
unqualified until actually observed.

## Delivery order

The following tranches are a scheduling guide. Mote's hard dependency edges govern
readiness; an independent ready task may move earlier. There is no requirement to
finish every item in one row before starting useful work in another.

| Tranche | Ordered focus | Exit / review |
| --- | --- | --- |
| **T1: Stable foundation** | O01.2 configuration; O01.3 runtime/migration roles; F02.1 organization records; H06.2 historical baseline; H04.1 workload/budgets | Protected runnable foundation and fixed compatibility/performance oracles. |
| **T2: Authority, recovery and resource boundaries** | F03.1/F03.2; H03.1 and O03.6 → O03.7; F02.3/F03.3/F03.4 → O03.8; O01.4/L01.2; O02 controls and H06.1 | Tested current-capability organization restore plus current authority, local operation and resource boundaries. |
| **T3: Collaboration and recoverability** | F04/F05/F06 browser workspace and L01.3 seed; O03.2/O03.4/O03.5 full identity/session recovery integration; O04.1/H03.2/O04.3 lifecycle; H02.1/O02.6 integrity/faults | Two-lab collaboration and tested engineering source-loss recovery, lifecycle controls and repair. |
| **T4: Complete and measure the repository** | W01 intake/export and W02 work/history/review; Q01.2 harness; H04.2 remediation; L01.4 guided story | Normal journeys complete and the declared engineering workload passes. |
| **T5: Accept one release** | H01–H06 reviews → L01.5 repeated local rehearsal → H00.1 integrated release → H00 review → L01.6 guide | Core-ready local demonstration with a coherent evidence dossier. |

**T1 completed, 14 October 2026:** O01.2, F02.1, O01.3, H06.2 and H04.1 are closed
with [source-bound receipts](evidence/core-hardening-t1/receipt.json). Configuration
and database privilege boundaries run in the package; the organization model has
checked reference transitions; the preserved old/current compatibility corpus passes;
the quick/stress workload and numeric targets are frozen. Actual performance qualification remains open.

**First T2 slice completed:** O03.1 and F02.2, with [source-bound evidence](evidence/core-hardening-t2/receipt.json).
ADR004 defines independently durable policy acknowledgment and closed recovery. V008
persists organization records, deterministic reviewed private legacy mapping, explicit
stewardship and atomic local events/receipts. The packaged migration preserves the
original historical oracle, rolls back forced interruption and rejects the old binary.
Legacy curator attribution no longer grants access after bootstrap. LocalOnly events
are not the independent witness; legacy recovery refuses this new organization state.

**F03.1 completed:** the pure authority model passes all 38 ADR cases, role/action
and disclosure checks, six 500-case properties and the existing organization suite
(71 tests total). See [its receipt](evidence/institutional-pilot/F03.1/receipt.json).
It preserves exact legacy grants and supplies safe access explanations.

**F03.2 completed:** PostgreSQL now evaluates the shared model under the policy guard;
organization, grant and credential changes share atomic `LocalOnly` history. Exact grant
retries, current offboarding/expiry, protected receipts and rollback/race behavior are
covered by [its evidence](evidence/institutional-pilot/F03.2/receipt.json). The
[mutation inventory](policy-transactions.md) defines the witness handoff. Public disclosure,
continuous delivery/worker checks, browser identity and independent recovery remain open.

**O03.6.1 completed:** the standalone signed witness passes 77 tests and 18 packaged
checks, including eight separate-process kill boundaries. See [its component receipt](evidence/institutional-pilot/O03.6.1/receipt.json).

**O03.6.2 completed:** complete fenced catalog enrollment, verified synchronous policy
acknowledgement, exact outcome resolution and bounded current SQL admission pass
441 distinct tests, 28 packaged checks and the preserved historical upgrade corpus.
See [its receipt](evidence/institutional-pilot/O03.6.2/receipt.json) and
[operator guide](policy-catalog.md). Historical LocalOnly receipts stay unchanged.
**O03.6.3 and O03.6 completed:** the integrated matrix passes 33 distinct process
cases, 250 authority regressions, 28 packaged checks and the preserved historical
upgrade. Separate stores, restrictive enrollment, interrupted commits, exact retries,
primary loss and independent old-writer fencing are qualified for current authority.
All six unchanged parent criteria pass; see [the qualification receipt](evidence/institutional-pilot/O03.6.3/receipt.json),
[parent review](evidence/institutional-pilot/O03.6/receipt.json) and
[reproduction guide](policy-process-qualification.md). Organization restore remains open.

**Next queue:** H03.1, verified backup automation against an isolated test store.
O03.6 is complete; H03.1 remains the blocker for O03.7.
O03.7 joins the journal and backup outputs; O03.8 qualifies the resulting path after
F02.3 and F03.3/F03.4. Bring this recovery milestone forward before browser workspace
expansion. F04.1, O01.4, O02.1 and O02.3 remain independently ready, but readiness
does not change the recovery priority. Use the preserved historical baseline for
every candidate; do not regenerate its oracle.

All six area reviews remain open. T1 strengthens H01/H06 and establishes H04's oracle;
storage controls, independent recovery and complete workflows remain unqualified.
T2 contains schema and authority integration risk, so continue one reviewable task at
a time (two-active upper bound) and review after the first migration/protocol pair.
No delivery date or institution-level capacity estimate is inferred from this tranche.

The exact per-tranche task lists are machine-readable in `coreHardening.tranches`
in the backlog. The original eight added implementation tasks are H02.1, H03.1,
H03.2, H04.1, H04.2, H06.1, H06.2 and H00.1; O03.6–O03.8 add the recovery foundations.
The area motes are deliberate acceptance reviews.

## Management and evidence rules

O03.6 is split into three ordered implementation slices, retaining every parent
acceptance criterion: **O03.6.1** standalone signed witness, **O03.6.2** complete
catalog enrollment/transaction adapter/current admission, and **O03.6.3** integrated
fault and artifact qualification. All three block O03.6; only their verified combined
result can unblock O03.7. The [witness contract](policy-witness.md) records the component
boundary. H03.1 remains independent backup work. Component completion does not enable
organization recovery or qualify an institutional failure domain.

- Keep at most **two active implementation tasks**, in areas with compatible file and
  schema ownership. Finish a reviewable change and its receipt before pulling another.
  This is a work-in-progress limit, not a request to create more agents.
- Before starting, verify blockers, acceptance procedure, fixture/source ownership and
  intended paths. Missing external inputs must remain visible. Split a task that grows
  beyond one reviewable responsibility rather than weakening its completion rule.
- After each tranche, update all six area states, record observed results and open
  defects, confirm the next ready queue and revise effort estimates using actual
  throughput. Task count is not a schedule; no completion date is inferred here.
- Any data corruption, unauthorized disclosure, restored revoked permission or false
  completion blocks the owning area and H00. Create a specific remediation mote that
  blocks the affected task/review, preserve the failing fixture and rerun affected
  evidence. These are not acceptable demonstration limitations.
- Every completion receipt names exact source/artifact/configuration/fixture identity,
  commands, raw results, negative cases and limits. A source change invalidates affected
  evidence. Document why unaffected evidence can be reused; do not blanket-rerun
  unrelated suites or silently reuse an older passing test count.
- H00.1 assembles one candidate and runs upgrade → collaboration/intake/correction/
  export/review → offboarding → capacity/stream failure → backup → revocation/withdrawal
  → original-source isolation → closed restore. Exact historical bytes and current
  permissions must agree throughout. H00 reviews this integrated outcome.

The main risks and owners are R1 authority/disclosure (H01), R2 capacity/shared-byte
cleanup races (H02), R3 independently durable restrictive policy (H03), R4 hidden
unbounded work (H04), R5 misleading operation/UI success (H05), and R6 historical
meaning/access drift during upgrades (H06). Their concrete controlling tasks are in
`coreHardening.risks`; each must have evidence or an open blocking remediation at review.

## Local engineering versus institutional acceptance

| Prove now | Still prove later |
| --- | --- |
| Real session/authority paths with an explicit test provider | F04.4 real institutional provider and approved guest onboarding |
| H06.1 metrics and actual local alert receiver | O02.2 named institutional destination and acknowledgment |
| H03.1 backup automation and O03.5 source-isolation restore | O03.3 independent institutional medium and Q03.1 replacement-host/failure-domain rehearsal |
| H03.2 local reference/hold/expiry mechanics and unsupported-erasure refusal | O04.2 actual institutional retention/erasure or exclusion decisions |
| H04.1/Q01.2/H04.2 synthetic workload and bounded results | Q01.1/Q01.3 permitted representative corpus and accepted institutional envelope |
| Automated browser/keyboard/accessibility and guide rehearsals | Q02 independent access review, actual users and actual screen-reader qualification; Q03 operator acceptance |

No institutional or mixed-input task is a blocker in EP9's engineering closure.
L01.5 now waits for the six core reviews, and L01.6 waits for H00, so final demo
readiness cannot quietly omit storage, recovery or performance. SITE01 remains after
the local demonstration. This is still one local-first product program; the later
institutional obligations are preserved.

## Plan validation and history

The [original validation receipt](evidence/core-hardening-planning.json) checks mote titles,
criteria, references, containment, dependency equality, cycle freedom, all six area
coverage, absence of institutional prerequisites on the core/local path, preserved
closed statuses and the ready queue. No new engineering task is closed by planning.

The [mote change manifest](evidence/core-hardening-mote-changes.json) lists created
and amended items. The [previous planning baseline](evidence/core-hardening-baseline/manifest.json)
preserves exact bytes needed to interpret earlier receipts as the plan evolves.
Current source and service test evidence remains untouched; this revision changes
planning, acceptance and coordination only.

The current recovery amendment is validated in [its planning receipt](evidence/core-recovery-planning/receipt.json).
The [change manifest](evidence/core-recovery-planning/changes.json) records added tasks
and dependencies; original task IDs, criteria and completed implementation evidence remain intact.

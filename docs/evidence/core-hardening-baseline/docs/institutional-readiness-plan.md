# Sanctity institutional readiness assessment and delivery plan

13 October 2026 assessment baseline, expanded into granular pilot epics and tasks.
Execution update, 14 October: F01's organizational contracts and O01.1's service
artifact are complete. Organizational enforcement, deployment hardening and
institutional acceptance remain open. The original assessment below describes the
starting point; current progress is in the linked epic/task guide.

The [epic and task guide](institutional-pilot-epics.md) is the current execution map:
seven required epics, two conditional epics, 19 acceptance gates and 71 granular tasks.
The original 18 mote IDs remain as gates, with explicit child-task dependencies.

**Immediate target, clarified 14 October:** first install on the product owner's
local computer, then demonstrate reliable repository workflows with a guide before
seeking institutional buy-in. No institution has approved a pilot. The
[local engineering/demo plan](local-demo-plan.md) is the current milestone; the
institutional readiness assessment and later qualification gates remain relevant.

**Verdict:** Sanctity has a substantial, tested repository foundation. It can support
a supervised evaluation with replaceable data today. It is not yet a service an
institution should depend on as its primary research repository. The largest gaps
are organizational authority, complete user workflows, service operations and
qualification on representative data. Another numerical processing tranche would
do little to close them.

The [core vision](vision.md) is the product direction. This plan takes precedence
over the earlier scientific-expansion sequence for choosing the next work. It does
not declare the existing PRD's alpha or production gates passed, remove their
requirements, or authorize production cutover.

## 1. What we can responsibly stand up

| Deployment | Assessment | Boundary |
| --- | --- | --- |
| Supervised engineering evaluation | Runnable now | Operator-managed PostgreSQL, private managed storage, existing CLI/API/console, approved replaceable data and declared support limits. External access requires a protected origin and TLS proxy. |
| Limited institutional beta | Target of this plan | Named service owner, institutional identity, governed labs/projects, complete curator workflows, independent recovery, measured capacity, and user/operator acceptance. |
| Institution's primary repository | Not ready | Requires sustained beta operation, an accepted support model, capacity and recovery evidence, and a separately approved source cutover. |

Confirmed deployment model: one institution hosts several labs and selected
external collaborators. Begin with one service, PostgreSQL and one qualified managed
filesystem backend. Use the institution's identity provider and backup facilities.
Separate catalog metadata and scientific bytes operationally while recovering them
as one repository. Neither distributed federation nor a general compute scheduler
is a prerequisite.

The first beta can use curated native BIDS when that serves the selected institution.
If its actual adoption depends on DICOM conversion or XNAT migration, qualification
of that specific source is a beta blocker. A native-BIDS beta does not complete the
original XNAT migration pilot or its broader PRD obligations.

## 2. Current delivery against the promise

| Promise | Implemented foundation | Missing evidence or behavior | Priority |
| --- | --- | --- | --- |
| Preserve and reproduce accepted data | Immutable content/interpretation/assessment identities; ordinary atomic acceptance; exact export; streamed, hashed managed files | Representative collection, deployment filesystem/power-loss behavior, disk-full handling, scheduled integrity checks and repair procedure | Beta blocker |
| Preserve concurrent and historical work | Transactional head checks, idempotent acceptance, receipts/audit/outbox, retained interpretation; conflict recovery in console | Qualification through supported release upgrades, independent operator workflow and realistic contention | Beta blocker |
| Organize labs and projects | Dataset identity, custodian and curator; scientific entities and frozen selections | First-class lab/project records, memberships, human-readable directory, ownership transitions and work tracking | Core product gap |
| Authenticate and enforce current access | Stable principals, hashed expiring API credentials, action-specific dataset/fixed-release grants, transactional checks and chunk-boundary delivery checks | Institutional login, browser sessions, external onboarding, account disablement, membership-derived authority, grant expiry and effective-access administration | Beta blocker |
| Discover, curate and share through an excellent interface | Real browser/API/PostgreSQL evidence for release inspection, metadata corrections, queries, fixed-release sharing and revocation | Known token/dataset-ID entry remains; no lab/project discovery; routine creation/import/export/operations require operator or internal capabilities; accessibility adoption gate remains open | Core product gap |
| Survive disaster | Catalog/blob backup scripts, integrity verification, closed restore gate, current-policy reconciliation and restored-token invalidation | Independently durable current authority/lifecycle history, automated off-host backups, actual recovery-point/time measurements, total-host-loss rehearsal | Beta blocker |
| Operate predictably | Bounded requests, connection limits, durable import leases/checkpoints, cancellation in bounded workflows | Installable release, deployment configuration, health/readiness endpoints, useful redacted diagnostics, metrics/alerts, capacity controls, support/runbooks | Beta blocker |
| Work at institutional scale | Indexed queries, bounded scientific profiles, retained optimization measurements | 10,000-entry import ceiling; representative concurrency, payload size, history growth and resource envelope; efficient paginated inventory and directory operations | Beta blocker at declared scale |
| Import existing institutional collections | Native imports, pinned converter and XNAT API/export adapters with scoped fixtures | Named source survey, reviewed mappings, restricted-source handling and representative accounting/cutover rehearsal | Required for each advertised source |
| Support QA and future processing | Exact bundles, independent destination authority, staged derivative admission, local producer/run evidence | Product-facing report/review status and integration administration; production execution backend remains later work | Preserve contracts; prioritize repository workflows |

Evidence anchors:

- [Dataset and access model](../modules/kernel/src/main/scala/sanctity/kernel/Lifecycle.scala),
  [authority rules](../modules/kernel/src/main/scala/sanctity/kernel/Authority.scala),
  [HTTP contract and operator boundary](http-api.md).
- [Transaction qualification](../modules/integration/src/test/scala/sanctity/postgres/TransactionSuite.scala),
  [HTTP authority qualification](../modules/integration/src/test/scala/sanctity/postgres/HttpSuite.scala),
  [durable operation cases](../modules/integration/src/test/scala/sanctity/postgres/OperationsSuite.scala).
- [Storage adapter](../modules/objects/src/main/scala/sanctity/objects/FileBlobStore.scala),
  [server assembly](../modules/service/src/main/scala/sanctity/app/ApiServer.scala),
  [migration runner](../modules/postgres/src/main/scala/sanctity/postgres/PostgresDatabase.scala).
- [Recovery implementation](../modules/postgres/src/main/scala/sanctity/postgres/PostgresRecovery.scala)
  and [backup](../scripts/backup-postgres.sh)/[restore](../scripts/restore-postgres.sh) scripts.
- [Latest retained test receipt](evidence/bold-timing-verification.json): 469 ordinary
  and 210 PostgreSQL tests, with crash/retry and closed-restore checks. These are
  retained development results, not a new institutional rehearsal.
- [Console adoption receipt](evidence/console/adoption.json): real backend rehearsal
  passed; human screen-reader qualification and adoption remain open.
- [Scale observations](evidence/query-scale-summary.json): a 10,000-row derivative
  observation workload still took roughly 8.6–9.7 seconds median in two after-change
  runs. Three warm samples and shared-host conditions establish no institutional SLA.
  Ordinary catalog discovery is a different workload and needs its own measurement.

### Consequential weaknesses to address directly

**Recovery needs a surviving source of current policy.** The current recovery CLI
can capture current grants and custody from the authoritative database. Its digest
checks snapshot identity, not freshness or authenticity. If that database and host
are lost, an old backup alone cannot establish which permissions were subsequently
revoked. Introduce an independently retained authority/lifecycle journal and a
verified recovery checkpoint. Acknowledged restrictive changes must survive the
declared failure model; if currency cannot be established, the gate stays closed.

**The single curator shortcut is insufficient for an institution.** Current authority
permits the dataset curator every action. A UUID is not a membership or an offboarding
policy. Replace implicit perpetual administrative rights with explicit governed
stewardship, current principal state and transactional authority evaluation. Preserve
historical attribution and migrate existing curators without orphaning datasets.

**The deployable service needs an operational boundary.** ApiServer starts through
the build tool, runs migrations at startup, and serves loopback behind an assumed
TLS proxy. Define an installable release, configuration validation, separate migration
and runtime database privileges, process supervision, diagnostics and recovery-mode
readiness. Connection limits alone do not establish a memory or throughput envelope;
the request semaphore must also be evaluated against streaming response lifetimes.

**Finite storage and lifecycle rules are essential even with collection disabled.**
Keeping every installed blob avoids unsafe reclamation but permits unbounded growth,
including abandoned work. Measure staged, accepted and backup bytes; reserve capacity
for imports, reject work before exhausting the volume, and alert operators. Document
retention, withdrawal, legal-hold handling where required by the institution, and
backup expiry. Automated garbage collection may remain disabled for the beta;
withdrawal and restore behavior cannot be left undefined. Institutions requiring
physical erasure need a qualified procedure before their affected data is admitted.

**Large-data usability is part of correctness in practice.** Complete release inventories
are currently serialized as a response and selections are paged from a loaded frozen
selection. Keep existing limits until representative measurements justify changes.
Add bounded inventory/directory/history APIs and measurable resource budgets; do not
raise the 10,000-entry ceiling or split scientific datasets merely to make a pilot fit.

## 3. Product and authority boundaries for the first beta

F01's [versioned organization contract](decisions/001-institutional-organization.md),
[authority matrix](decisions/002-institutional-authority.md) and
[lifecycle/migration contract](decisions/003-institutional-lifecycle.md) now make
these working choices concrete for implementation:

- Each project has an owning lab and explicit collaborators. Each dataset has one
  primary project initially; other project references confer no new access. Lab,
  project and dataset identity stay separate. Existing releases retain their IDs.
- Reader, contributor and steward are proposed role bundles. Contribution and
  acceptance remain distinct actions. Managing lab membership does not automatically
  disclose every dataset; permitted project-role assignments must be explicit.
- Membership-derived access ends when the membership ends. An independent grant has
  its own scope, expiry and provenance and is visible in effective-access inspection.
  Disabling a principal stops all their current access, including curator paths.
- External collaborators use verified identities and explicit admission. Matching email
  addresses never merge accounts automatically. Data sharing defaults to an explicit
  fixed release/view; access to future releases requires a deliberate choice.
- Administrative transfers and last-steward removal have an audited recovery path.
  Mutable organization and access state does not rewrite scientific history.
- Project work initially consists of assigned curation/review tasks, notes, activity
  and dataset status. Notes/tasks/attachments obey dataset disclosure limits. Review
  outcomes bind exact releases; later changes make prior review visibly historical.

## 4. Delivery sequence and exit gates

| Stage | Outcome | Exit evidence |
| --- | --- | --- |
| Scope and deployment brief | Name an institution, service owner, curator and identity/IT contacts; choose collections, source types, capacity and support boundaries | SITE01 records actual constraints and who accepts each gate; engineering can start using the stated defaults meanwhile |
| Institutional foundation | Labs/projects, current membership authority, institutional sign-in, authorized discovery and usable access administration | F01–F06: two labs and an external collaborator complete the browser journey; denied discovery, stale sessions, revocation and steward offboarding pass |
| Operable and recoverable service | Reproducible installation, monitoring, bounded resources, off-host recovery and lifecycle controls | O01–O04: clean-host installation/upgrade, alerts, independent policy replay and withdrawal after backup; no reliance on the lost source server |
| Complete repository workflows | Supported intake, progress/retry/cancel, curation, release/history navigation, full export, project review work | W01–W02: curator and researcher complete work without database access or a developer carrying out routine steps |
| Institutional qualification | Representative correctness/capacity, independent access review, accessibility, operator handover and beta rehearsal | Q01–Q03: source-bound results, named human acceptance and a bounded service envelope |
| Primary-service decision | Sustained successful beta and source reconciliation | Service owner approves support and cutover after the gates; retain the original source until reconciliation and recovery evidence support the transition |

O01 packaging can begin alongside F01; operation and recovery work must not wait
until the interface is finished. The detailed dependency graph is retained in
[mote IDs](evidence/institutional-readiness-motes.json) and the
[backlog specification](institutional-readiness-backlog.json). Dependencies express
integration order, not a requirement to wait for unavailable institutional inputs
before doing independent engineering.

### Immediate six-mote foundation tranche

F01–F06 are now six acceptance gates with 19 granular implementation/qualification
tasks beneath them, grouped across the workspace and identity epics. The table below
retains the foundation outcomes. Its main inputs describe technical relationships;
the [granular backlog](institutional-readiness-backlog.json) defines the actual
leaf-level blockers so independent engineering need not wait for an entire gate.

| ID | Work | Main inputs | Acceptance |
| --- | --- | --- | --- |
| F01 | Resolve lab/project/role, discovery, sharing and offboarding contracts | None | ADR, role/action matrix and migration examples; no implicit access from project links or email matching |
| F02 | Persist labs, projects, memberships and dataset placement; expose governed administration | F01 | Reference/PostgreSQL agreement, checked migration of existing datasets, concurrent membership changes and audited stewardship transitions |
| F03 | Enforce current organizational authority everywhere | Organizational records from F02 | Pure/SQL agreement; principal disablement, membership removal and grant expiry affect listings, counts, details, writes, receipts and streams without widening existing fixed-release grants |
| F04 | Add one institutional identity adapter and browser-session lifecycle | F01 | Verified stable bindings, secure session handling, logout/deactivation and expiry; simulated provider contract plus a real selected-provider rehearsal before beta |
| F05 | Deliver lab/project discovery and access administration in the console | F03, F04 | Login → authorized projects → datasets → existing curation; readable people/roles, effective access, sharing and revocation without manually entering UUIDs |
| F06 | Qualify the institutional foundation end to end | F05 | Two labs, several roles and an external collaborator; negative disclosure, concurrent role changes, offboarding, upgrade of existing fixtures and accessibility evidence |

This tranche delivers the organizational foundation. It does not by itself certify
institutional readiness: independent recovery, intake/export and qualification remain
required. It is a multi-stage implementation, not an overnight completion promise.

### Operations and workflow completion

O01 supplies a versioned build artifact, deployment manifest, TLS/origin configuration,
secrets handling, separate migration command, graceful shutdown and clean-install/upgrade
runbooks. O02 supplies health/readiness, redacted structured diagnostics, operator audit
inspection, storage/queue/database metrics, tested alerts, concurrency limits and capacity
reservations. Recovery-required service must report unready and refuse application work.

O03 schedules verified off-host catalog/blob backups, independently persists current
authority changes, and proves total-host-loss recovery. Specify policy-journal durability
at acknowledgment and replication failure behavior so recovery never guesses that an
old grant is still valid. O04 extends that contract to quarantine, withdrawal and any
required physical deletion/backup expiry, including shared references and restore.

W01 exposes authenticated dataset creation, staged intake and durable full-export
operations through the same application contracts. Start with an approved server-side
intake location and/or bounded resumable upload chosen for the pilot; never accept
arbitrary server paths. Report real scope/progress, verify bytes, enforce staging quotas,
support interrupted work and current-authority cancellation/delivery, and preserve
source evidence under its own restrictions.

W02 adds a project overview, searchable dataset directory, release history, curation
queue, assigned review tasks and release-bound findings/report status. Keep acceptance,
automatic checks and human review distinct. W03 is a later bounded visualization/report
integration using existing outputs; untrusted reports and thumbnails need the same
authorization and appropriate rendering isolation. It introduces no new analysis method
and is not a beta blocker unless the chosen users require that workflow.

## 5. What qualification must prove

Q01 uses the selected collection and a documented growth/concurrency model to measure
discovery, metadata inspection, pagination, correction/acceptance, import/export,
revocation, history growth and recovery. Include large individual images, many small
files, realistic grants, concurrent uploads/readers, interrupted transfers, database
outage and disk exhaustion. Record hardware, versions, source identity, peak memory,
throughput and latency distributions; retain correctness oracles and raw results.

The PRD's existing targets include 250 ms p95 selective queries over one million entries
with 20 readers, a 100 GiB streamed payload, recovery point at most 15 minutes and
catalog/API recovery within four hours when blobs survive. These remain targets,
not achievements. SITE01 must explicitly decide which apply to the beta, specify
full-blob-loss recovery separately and record any narrower envelope. Without an accepted
scope change, failing an applicable target blocks qualification. Restrictive access and
lifecycle changes need stronger recovery treatment than silently rolling back within
the scientific-data recovery window.

Q02 exercises a role/action threat matrix across API and UI, unauthorized directory and
count leakage, expired/revoked sessions and grants, restricted source/report surfaces,
and browser security introduced by the selected login/session design. An independent
reviewer checks those boundaries. Representative curators/researchers complete the
core tasks, including conflict recovery, keyboard and actual screen-reader use; close
the existing CON-03 adoption gap with human evidence. Automated browser checks alone
do not meet that gate.

Q03 has an operator who did not implement the feature install, upgrade, diagnose a
failure, restore onto replacement infrastructure and recover pending work from the
runbooks. Revoke a collaborator and withdraw a dataset after a backup, then lose the
original server: neither may become accessible after restore. Demonstrate alert delivery,
backup age/integrity detection, operator escalation and the measured recovery envelope.
Agree a beta observation period that includes a scheduled backup cycle and an operator
recovery exercise before considering primary-service cutover.

## 6. Capacity, ownership and scope control

Planning assumption: two engineers covering backend/operations and frontend/integration,
with a named part-time institutional operator and curator, plus timely identity-provider
and security-review access. Initial effort allowance: foundation 6–10 engineer-weeks,
operations 5–8, workflows 4–6, qualification 3–5. The total is approximately 18–29
engineer-weeks; allow roughly 12–18 elapsed weeks with overlap and integration time.
This is an initial planning range, not a measured forecast. Re-estimate after F01 and
O01, and again after F06, using actual throughput and institutional dependencies.
The granular backlog adds detail rather than a new calendar commitment. Its gate
reviews are acceptance work, not a second implementation estimate; institutional
waiting time is separate from engineering effort.
It excludes broad DICOM/XNAT qualification, a general processing backend and new analyses.
One engineer, absent frontend capacity or unavailable institutional inputs changes the
calendar substantially.

SITE01 records accountable people and obtains the actual source/data classification,
identity-provider details, hosting/storage constraints, initial capacity, growth,
retention/withdrawal requirements, backup medium, acceptable downtime and support hours.
No person or institution is presumed to have accepted those responsibilities yet.

Keep scientific regression checks, fix defects affecting the selected data, and retain
the existing processing contract. Defer new numerical recipes, general scheduling,
multi-installation federation, additional storage backends and generic task-management
features until a concrete core workflow requires them. C01 separately qualifies the
specific DICOM/XNAT collection when selected; it becomes a launch dependency whenever
SITE01 makes that source part of the beta. Original PRD gates remain separate and visible.

A mote closes on its stated behavior and retained evidence. The institutional program
closes only when SITE01, F01–F06, O01–O04, W01–W02 and Q01–Q03 pass, plus any source or
visualization requirement selected by SITE01. Planning completion is not implementation
completion. The current tranche receipts and original requirements remain historical
evidence; creating this plan does not change their qualification status.

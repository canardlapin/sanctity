# Sanctity institutional pilot epics and tasks

Planned 13 October 2026; execution updated 14 October 2026.

**Immediate milestone:** a dependable installation on the product owner's computer,
with a guided demo and documentation to earn institutional interest. There is no
institutional approval yet. [The local demonstration plan](local-demo-plan.md)
defines EP0 and its six granular tasks; institutional selection/acceptance follows
that milestone and does not block its engineering work.

The first engineering tranche completes F01.1–F01.3 and the F01 design gate, plus
O01.1 service packaging. See the [organization contract](decisions/001-institutional-organization.md)
and [service distribution guide](service-distribution.md). The remaining product
implementation and institutional qualification are open; no epic is complete.
L01.1's local engineering contract is also complete; actual retained installation,
the full demonstration and final guides remain L01.2–L01.6.

The program is **bd-01M4W3ZN8Z16J30J8EYPWQERQC**. It advances the [core vision](vision.md): a
trustworthy repository with excellent lab/project workflows, governed collaboration,
and dependable institutional operation. The deployment model is one institution,
several labs and selected external collaborators.

The [readiness assessment](institutional-readiness-plan.md) explains the gaps.
This execution plan contains **seven required epics, two conditional epics, 19 acceptance
gates and 71 granular tasks** (65 required, six conditional). The original 18 mote IDs
are preserved as acceptance gates; they now have implementation/qualification tasks
beneath them. The planning refinement closed nothing; subsequent execution is
recorded in mote and per-task receipts under `docs/evidence/institutional-pilot/`.

## How work is organized

The containment hierarchy is program → epic → acceptance gate → task. Blocking
dependencies are separate edges. A task can depend on an earlier task's output
without waiting for its entire epic to close. Gate closure is a deliberate review
of integration and evidence, not a second implementation of its children.

Each task in mote includes its scope, accountable role, three observable acceptance
criteria, dependencies, verification procedure, expected evidence location, starting
code/evidence references and external inputs. The complete machine-readable version
is [the backlog specification](institutional-readiness-backlog.json); the
[ID map](evidence/institutional-readiness-motes.json) maps every key to its mote.

There are 52 engineering tasks, seven requiring both engineering and institutional
inputs, and 12 institutional qualification/decision tasks. Engineering availability
does not supply real accounts, data permission, human review or acceptance owners.
Keep those outcomes open until observed evidence is available.

## Epic outcomes and launch status

| Epic | Outcome | Tasks | Launch status |
| --- | --- | --- | --- |
| EP0: Reliable local installation and guided repository demonstration | A repeatable local install and real browser journey, with evidence and guides ready for institutional discussions. | 6 | Immediate milestone; required |
| EP1: Labs, projects and a usable collaboration workspace | A lab steward and researcher can find, organize and share their projects without internal IDs or database assistance. | 11 | Required |
| EP2: Institutional identity and current permissions | Institutional and approved external identities receive only their current, explicit authority. | 8 | Required |
| EP3: Installable and observable institutional service | An operator can install, upgrade, monitor and bound a supported service. | 10 | Required |
| EP4: Durable storage, withdrawal and independent recovery | Loss of the original host does not lose the declared recoverable data or resurrect revoked access and withdrawals. | 8 | Required |
| EP5: Complete intake, curation, export and project work | Curators and researchers complete everyday repository work through supported product workflows. | 10 | Required |
| EP6: Institutional pilot evidence and operator handover | A named institution can make an evidence-backed decision to run a bounded pilot. | 12 | Required |
| EP7: Qualify the selected DICOM or XNAT source | The institution's required source collection is imported with explicit scientific coverage and complete accounting. | 3 | Conditional on SITE01.3 |
| EP8: Integrate a bounded visualization or QA report workflow | Researchers inspect an existing image/report workflow under the repository's ordinary authority and lineage contracts. | 3 | Conditional on SITE01.3 |

EP7 and EP8 are dormant scope options. SITE01.3 records whether each is needed;
if selected, it adds that epic as a blocker of both the program and Q03.3 before
its implementation begins. A deferred option stays explicitly deferred, never
closed as though implemented. `mote ready` reports dependency readiness, not scope
authorization; inspect the `pilot-conditional` tag and the recorded scope decision.

## First execution sequence

Execution order for the independent tracks:

1. **F01.1 → F01.2 → F01.3 → F01 review:** settle organization boundaries, action
   rules and offboarding/migration behavior. This design sequence is complete.
2. **O01.1 → O01.2 and O01.3 → O01.4 → O01 review:** package, configure, separate
   database privileges and demonstrate installation/upgrade. O01.1 is complete;
   O01.2 and O01.3 are next.
3. **L01.1 → L01.2 → L01.3 → L01.4 → L01.5 → L01.6 → L01 review:** define the local
   profile, install the packaged service, seed realistic accounts/data, demonstrate
   complete workflows, repeat failure/restart checks and finish the guides.
4. **After L01, SITE01.1 → SITE01.2 → SITE01.3 → SITE01 review:** obtain real
   institutional owners, data/infrastructure inputs and accepted targets through
   explicitly authorized discussions. These are later acceptance inputs.

After the F01 gate, implement F02.1 reference records, then F02.2 persistence and
F03.1 pure policy rules. F03.2 connects authority to transactions; F02.3 exposes
safe administration. Identity and workspace tasks then consume those contracts.
This produces observable product behavior while operation/recovery work continues.
F02.1 is the next ready organization task. The ADRs are implementation inputs;
the 38 authorization cases are design expectations for F03 tests, not passing
institutional authorization evidence.

Useful commands:

```sh
mote show bd-01M4W3ZN8Z16J30J8EYPWQERQC
mote children bd-01M4W5P2G3FCFHYJ3J9VNBPCRM
mote show bd-01M4W5P4K7KNJYB91PB7EMWWCV
mote ready
```

## Completion and evidence rules

- Close a task only when every stated criterion passes and its evidence identifies
  the exact source/artifact, configuration, procedure, fixtures, results and limits.
- Use real failures and negative cases where relevant: unauthorized discovery,
  revoked sessions, concurrent edits, interrupted delivery, exhausted capacity and
  loss of the original server. Preserve existing scientific and authority laws.
- A synthetic provider cannot close real institutional identity qualification.
  Automated accessibility cannot close actual screen-reader qualification. A restore
  using the surviving original database cannot close independent disaster recovery.
- Keep a failing or not-run criterion open. Create linked remediation work when needed,
  and rerun the affected gate. Split unexpectedly large implementation tasks into
  blocking child work rather than silently expanding or weakening acceptance.
- Required institutional input includes named acceptance, not an inferred willingness
  or a newly invented approval. Record unavailable inputs without pretending the
  result was demonstrated.
- The program closes through Q03.3's evidence manifest and named pilot go/no-go.
  Source cutover to an institution's primary repository remains a separate decision.

## Milestones visible to users

| Milestone | Observable result | Required evidence |
| --- | --- | --- |
| Local demo (immediate) | A retained workstation installation and complete guided repository story with reliable reset/restart and checked guides | L01; synthetic local identity/data are clearly identified |
| Deployable foundation | An operator starts and upgrades a pinned service; organizational contracts are explicit | F01 and O01 |
| Governed workspace | Staff and a guest sign in, discover projects and collaborate across two labs | F02–F06, including real-provider qualification in F04 |
| Everyday repository | Curators ingest/review/accept; researchers retrieve exact data; labs track review work | W01–W02 with O02 resource/observability controls |
| Independent recovery | Replacement infrastructure restores retained data and current restrictions without the old server | O03–O04 |
| Pilot decision | Real users and an operator demonstrate the agreed institutional envelope | SITE01, Q01–Q03 and all selected conditional epics |

The ordering above describes outcomes, not extra dependency barriers. The backlog's
leaf-level graph is authoritative. Earlier effort estimates remain provisional;
71 tasks do not imply 71 equal-sized days, and gate reviews are not duplicate feature
estimates. Re-estimate using actual early-task throughput and institutional lead times.

## EP0 Reliable local installation and guided repository demonstration

Mote: `bd-01M4X5M9TGQV4QYWRASHCZQV0V`. Immediate engineering milestone before
institutional pilot discussions. Its scope and detailed story are in the
[local demo plan](local-demo-plan.md); every task has acceptance criteria in mote.

### L01 Qualify the local installation, demo and institutional discussion pack

Acceptance gate: `bd-01M4X5M9Y1A8NCC8SRECRA62AE`.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| L01.1 | Specify the local installation and demonstration contract | F01 | engineering |
| L01.2 | Install and manage a retained local demonstration instance | L01.1, O01.4 | engineering |
| L01.3 | Seed the local two-lab demo and verified test-provider accounts | L01.2, F06.1 | engineering |
| L01.4 | Complete the guided local repository demonstration | L01.3, F06.2, W01, W02 | engineering |
| L01.5 | Qualify repeated local demo installation and failure recovery | L01.4 | engineering |
| L01.6 | Publish the local quickstart, user guide and institutional discussion pack in the repository | L01.5 | engineering |

Local completion leaves real-provider, human accessibility, independent review,
institutional data permission and operator acceptance explicitly open. It does not
authorize external publication, institutional messages or primary-repository cutover.

## EP1 Labs, projects and a usable collaboration workspace

Mote: `bd-01M4W5P2G3FCFHYJ3J9VNBPCRM`. Required for pilot acceptance.

A lab steward and researcher can find, organize and share their projects without internal IDs or database assistance.

Epic acceptance:

- The organizational contract and migration preserve existing scientific releases and attribution.
- Authorized project discovery and collaboration screens work across two labs; unauthorized names/counts and revoked access remain hidden.
- The two-lab browser/API/PostgreSQL journey and historical-data migration have retained passing evidence.

### F01 Specify lab, project, role and offboarding contracts

Acceptance gate: `bd-01M4W40J9S9Z7GFMS5DR2SZEV5`.

A small organizational model with explicit authority and a migration path from the existing curator model.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| F01.1 | Specify lab, project and dataset identity boundaries | None | engineering |
| F01.2 | Specify role actions and current-access decisions | F01.1 | engineering |
| F01.3 | Specify offboarding, migration and stewardship recovery | F01.2 | engineering |

### F02 Persist governed labs, projects and memberships

Acceptance gate: `bd-01M4W40JE6XGVG1R6TSQQZ6P6S`.

Reference and PostgreSQL catalogs support durable organizational records and controlled administration.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| F02.1 | Implement organization records and reference-catalog transitions | F01 | engineering |
| F02.2 | Migrate PostgreSQL organization records and legacy datasets | F02.1 | engineering |
| F02.3 | Expose governed organization and stewardship administration | F02.2, F03.2 | engineering |

### F05 Deliver authorized lab and project discovery in the console

Acceptance gate: `bd-01M4W40JSS0HZY131BNTKV1QF3`.

A signed-in user finds permitted projects/datasets and manages collaboration without typing internal IDs.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| F05.1 | Expose bounded authorized lab, project and dataset directories | F02.3, F03.3 | engineering |
| F05.2 | Build institutional login and lab/project navigation in the console | F05.1, F04.3 | engineering |
| F05.3 | Build collaborator, sharing and effective-access screens | F05.2, F03.3 | engineering |

### F06 Qualify the two-lab institutional foundation journey

Acceptance gate: `bd-01M4W40JXJ4C805E7NBZ0WCDMF`.

A retained end-to-end demonstration of sign-in, organization, sharing and offboarding across two labs.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| F06.1 | Create the reusable two-lab institutional workflow fixture | F05, F03.4 | engineering |
| F06.2 | Qualify foundation races, offboarding and disclosure boundaries | F06.1 | engineering |

## EP2 Institutional identity and current permissions

Mote: `bd-01M4W5P2KP3KB4M3C8PS3CYAGZ`. Required for pilot acceptance.

Institutional and approved external identities receive only their current, explicit authority.

Epic acceptance:

- Membership, stewardship, principal disablement and expiring grants are evaluated consistently at every boundary, including long-running work.
- One real institutional identity provider and the chosen browser-session design are qualified; email matching never links identities.
- Offboarding, revoked/expired sessions, stale contexts and fixed-release boundaries pass positive and negative tests.

### F03 Enforce current memberships, principal state and expiring access

Acceptance gate: `bd-01M4W40JJBE0KQ3K3H365X8033`.

Every repository access path reflects current organizational authority, with explainable effective access.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| F03.1 | Implement pure role and effective-access evaluation | F02.1 | engineering |
| F03.2 | Enforce organizational authority inside PostgreSQL transactions | F03.1, F02.2 | engineering |
| F03.3 | Apply disclosure and effective-access rules to read surfaces | F03.2 | engineering |
| F03.4 | Recheck offboarding and expiry during delivery and processing | F03.2 | engineering |

### F04 Implement institutional sign-in and browser-session lifecycle

Acceptance gate: `bd-01M4W40JP4T8D7RY21HG6YKHF3`.

One institutional identity provider authenticates stable principals with a usable and revocable browser session.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| F04.1 | Add verified institutional identity bindings and provider configuration | F01.3, F02.2 | engineering |
| F04.2 | Implement institutional browser sign-in and secure sessions | F04.1, F03.2 | engineering |
| F04.3 | Implement guest admission, session revocation and account offboarding | F04.2, F03.4 | engineering |
| F04.4 | Qualify the selected real institutional identity provider | F04.3, SITE01.2, O01.4 | institutional |

## EP3 Installable and observable institutional service

Mote: `bd-01M4W5P2RA31CS3MZVC1K2GBXS`. Required for pilot acceptance.

An operator can install, upgrade, monitor and bound a supported service.

Epic acceptance:

- A versioned artifact installs on a clean host with separate migration/runtime privileges and documented configuration.
- Health, diagnostic, audit, resource and alert surfaces support real operator actions without disclosing secrets or private scientific metadata.
- Database outage, storage exhaustion, interruption and upgrades preserve accepted state within the supported envelope.

### O01 Package an installable service with controlled upgrades

Acceptance gate: `bd-01M4W40K19EDEPHZA7Y20F340Z`.

A clean institutional staging host can run a pinned Sanctity release without using sbt as the service manager.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| O01.1 | Build a pinned installable Sanctity service artifact | None | engineering |
| O01.2 | Validate deployment configuration and protected service startup | O01.1 | engineering |
| O01.3 | Separate schema migration from least-privilege runtime access | O01.1 | engineering |
| O01.4 | Exercise packaged install, upgrade and release evidence in CI | O01.2, O01.3 | engineering |

### O02 Make service health, storage limits and failures observable

Acceptance gate: `bd-01M4W40K6BGH2A78J8820FP8M6`.

Operators see and can respond to resource exhaustion, failed work and unavailable dependencies before data stewardship fails.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| O02.1 | Expose liveness, readiness and redacted request diagnostics | O01.2 | engineering |
| O02.2 | Add operational metrics and tested alert delivery | O02.1 | mixed |
| O02.3 | Enforce admission limits across requests, streams and background work | O01.2 | engineering |
| O02.4 | Account for storage and reserve capacity before intake | O02.3 | engineering |
| O02.5 | Expose protected audit and failed-operation inspection | F03.3, O02.1 | engineering |
| O02.6 | Qualify service faults and operator integrity procedures | O02.2, O02.4, O02.5 | engineering |

## EP4 Durable storage, withdrawal and independent recovery

Mote: `bd-01M4W5P2YQVKVP7ERG6ZGEZD5C`. Required for pilot acceptance.

Loss of the original host does not lose the declared recoverable data or resurrect revoked access and withdrawals.

Epic acceptance:

- Catalog/blob backups and independently durable current policy/lifecycle history survive the declared failure domains.
- Restore remains closed on missing, stale, incomplete or unauthenticated policy evidence and invalidates old sessions, credentials and leases.
- Revocation/withdrawal after backup, shared references, integrity damage and total-host-loss recovery have measured results; erasure obligations have an enforced admission boundary.

### O03 Preserve current authority independently and automate disaster recovery

Acceptance gate: `bd-01M4W40KBWB13MJQHFAGJEGYZR`.

Catalog/blob loss cannot force recovery to trust stale permissions or credentials.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| O03.1 | Specify policy-journal durability and recovery trust | F01.3, O01.2 | engineering |
| O03.2 | Persist and replicate independent authority history | O03.1, F03.2, F04.3 | engineering |
| O03.3 | Automate verified off-host catalog and blob backups | O03.1, O01.2 | mixed |
| O03.4 | Restore a closed service with verified current authority | O03.2, O03.3 | engineering |
| O03.5 | Automate total-host-loss and revoked-access recovery exercises | O03.4 | engineering |

### O04 Enforce quarantine, withdrawal and recovery lifecycle policy

Acceptance gate: `bd-01M4W40KFJVWZAM6RZ8DDH4P2D`.

Withdrawal and any required erasure have explicit effects on current service and restored copies.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| O04.1 | Implement quarantine and withdrawal as current lifecycle controls | O03.2, F03.3 | engineering |
| O04.2 | Implement retention accounting and the required erasure boundary | O04.1, O03.3, SITE01.2 | mixed |
| O04.3 | Prove withdrawal survives backup replay and interrupted recovery | O04.2, O03.5 | engineering |

## EP5 Complete intake, curation, export and project work

Mote: `bd-01M4W5P33FES8TED58TNGTA873`. Required for pilot acceptance.

Curators and researchers complete everyday repository work through supported product workflows.

Epic acceptance:

- Governed creation, approved intake, review/acceptance, retry/cancel and verified export work without direct database edits.
- Project tasks, notes, release history and review status are usable and obey data permissions.
- Every review/result identifies exact releases; failed or partial operations cannot appear completed or scientifically accepted.

### W01 Complete authenticated intake and reproducible export workflows

Acceptance gate: `bd-01M4W40KKEG53SX6WJ17NQVQSV`.

Curators can create and populate a dataset and researchers can obtain verified full exports through supported product workflows.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| W01.1 | Expose authorized dataset creation and intake registration | F02.3, F03.3, O02.4 | engineering |
| W01.2 | Implement bounded and resumable native staging intake | W01.1, L01.1 | engineering |
| W01.3 | Expose durable import status, retry and cancellation | W01.2 | engineering |
| W01.4 | Build the intake and curation workbench | W01.3, F05.2 | engineering |
| W01.5 | Implement durable full-export planning and verified delivery | F03.4, O02.4, W01.1 | engineering |
| W01.6 | Build export retrieval and operation recovery in the console | W01.4, W01.5 | engineering |

### W02 Add project curation, release history and review work

Acceptance gate: `bd-01M4W40KPVEFQSMWFEDZZC69SW`.

A project shows its datasets, responsibilities and outstanding review work in one coherent workflow.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| W02.1 | Page release inventories and history without loading whole responses | F03.3, F05.1 | engineering |
| W02.2 | Persist assigned curation tasks, notes and review state | F02.3, F03.3 | engineering |
| W02.3 | Build project overview and the curation work queue | W02.1, W02.2, W01.4 | engineering |
| W02.4 | Show release-bound checks, reports and human review outcomes | W02.3 | engineering |

## EP6 Institutional pilot evidence and operator handover

Mote: `bd-01M4W5P3AZ1X514RZY328NKP66`. Required for pilot acceptance.

A named institution can make an evidence-backed decision to run a bounded pilot.

Epic acceptance:

- The service owner accepts source/data boundaries, capacity, recovery objectives, responsibilities and any conditional integrations.
- Representative correctness/load evidence, independent access review, actual user/accessibility results and operator-led recovery meet that scope.
- A recorded pilot go/no-go distinguishes open blockers from accepted limits; primary-repository migration remains a separate decision.

### SITE01 Define institutional beta scope, owners and acceptance envelope

Acceptance gate: `bd-01M4W40J6HFTNMMMEH40K1KSYM`.

A named deployment brief replacing planning assumptions with the selected institution's actual needs.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| SITE01.1 | Name the pilot institution, service owner and acceptance roles | L01 | institutional |
| SITE01.2 | Inventory pilot data, infrastructure and lifecycle constraints | SITE01.1 | institutional |
| SITE01.3 | Set pilot targets and activate only required integrations | SITE01.2 | institutional |

### Q01 Qualify representative data and the institutional capacity envelope

Acceptance gate: `bd-01M4W40KZWFPTRA33A443C4DM8`.

A published supported envelope describes measured capacity and correct behavior on representative institutional data.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| Q01.1 | Build a permitted representative corpus and correctness oracle | SITE01.2, F01 | mixed |
| Q01.2 | Build repeatable institutional workload and failure harnesses | SITE01.3, F06, W02.1, O02.3 | engineering |
| Q01.3 | Qualify and publish the supported pilot capacity envelope | Q01.1, Q01.2, W01, W02, O02 | mixed |

### Q02 Close access review and human usability adoption gates

Acceptance gate: `bd-01M4W40M3Y7795AWYWHVAA9K68`.

The institutional collaboration and interface claims have independent and human acceptance evidence.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| Q02.1 | Obtain independent review of access and disclosure boundaries | F06, F04.4, W02, O04 | institutional |
| Q02.2 | Have curators and researchers complete pilot workflows | W02, F04.4, SITE01.3 | institutional |
| Q02.3 | Close keyboard and actual screen-reader adoption gaps | W02, F05 | institutional |

### Q03 Rehearse clean-host recovery and institutional operator handover

Acceptance gate: `bd-01M4W40M7DJRQJWNCBEPR0AFEN`.

An operator can own a bounded institutional beta and recover it without the original engineer or server.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| Q03.1 | Rehearse install, upgrade and disaster recovery with an operator | O01, O02, O04, F04.4, Q01, SITE01.3 | institutional |
| Q03.2 | Exercise staging operation, support and escalation ownership | Q03.1, Q02, Q01 | institutional |
| Q03.3 | Assemble evidence and record the institutional pilot go or no-go | SITE01, EP0, EP1, EP2, EP3, EP4, EP5, Q01, Q02, Q03.2 | institutional |

## EP7 Qualify the selected DICOM or XNAT source

Mote: `bd-01M4W5P3J2MBRFGCM66SX67JRG`. Implement only after explicit SITE01.3 activation.

The institution's required source collection is imported with explicit scientific coverage and complete accounting.

Epic acceptance:

- Activate only when SITE01 selects this source for the pilot and adds the epic as a program blocker.
- Named source inventory, reviewed mapping/fixtures and restricted-evidence handling are qualified.
- A representative interrupted import and reconciliation leave no unexplained source objects or silent scientific transformations.

### C01 Qualify the selected DICOM or XNAT source when adoption requires it

Acceptance gate: `bd-01M4W40MB0S9CQZS1C0HBYRRFT`.

Only the advertised source installation and acquisition profiles are claimed as qualified.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| C01.1 | Survey the selected DICOM or XNAT source and account for scope | SITE01.3 | institutional |
| C01.2 | Qualify source mappings and supported conversion profiles | C01.1, W01.3 | engineering |
| C01.3 | Rehearse interrupted migration and final source reconciliation | C01.2, W01, O04 | institutional |

## EP8 Integrate a bounded visualization or QA report workflow

Mote: `bd-01M4W5P3SWC3QG9EF5RNM6BXRA`. Implement only after explicit SITE01.3 activation.

Researchers inspect an existing image/report workflow under the repository's ordinary authority and lineage contracts.

Epic acceptance:

- Activate only when SITE01 records the needed workflow and adds the epic as a program blocker.
- The integration consumes versioned inputs/results without introducing a new analysis method or general scheduler.
- Current permissions, untrusted report isolation, large-payload behavior and accessibility are qualified.

### W03 Integrate bounded image or QA report inspection when required

Acceptance gate: `bd-01M4W40KTCGH0AQ4YQB04DV68Y`.

A selected visualization workflow consumes existing versioned data or reports through normal access contracts.

| Task | Deliverable | Blocking inputs | Work kind |
| --- | --- | --- | --- |
| W03.1 | Select one pilot visualization or QA report contract | SITE01.3 | mixed |
| W03.2 | Integrate the selected viewer or report surface | W03.1, W02.4 | engineering |
| W03.3 | Qualify visualization authority, resources and usability | W03.2, Q01.1 | mixed |

## Plan provenance and verification

The [original backlog](evidence/institutional-readiness-backlog-v1.json) and
[original mote map](evidence/institutional-readiness-motes-v1.json) preserve the
previous planning baseline. The new map retains the same program and gate IDs.
The [planning validation receipt](evidence/institutional-pilot-planning.json)
records graph, scope coverage, mote/body/dependency and document-link checks.
It is planning evidence, not a claim of application, security or pilot qualification.

The [current execution/plan receipt](evidence/institutional-pilot-progress.json)
records the local-first revision, completed foundation tasks and current mote graph.
The [previous backlog](evidence/institutional-readiness-backlog-v2.json) and
[previous ID map](evidence/institutional-readiness-motes-v2.json) preserve the
pre-local-demo plan. Program and existing task IDs remain stable.


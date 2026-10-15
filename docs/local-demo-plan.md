# Local installation and demonstration before an institutional pilot

14 October 2026. Immediate delivery milestone, based on the product owner's direction:
the first installation is on their local computer; there is no institutional approval
yet. Build reliable engineering and a convincing demo with a guide before proposing
a pilot. The one-institution/several-labs/selected-guests model remains the intended
product destination, not a claim that an institution has agreed to host it.

This document specifies the local engineering acceptance target (L01.1). It is not
the completed installation guide or a claim that the full demo is available today.
The existing [service distribution](service-distribution.md) is the first working
artifact. The [pilot backlog](institutional-readiness-backlog.json) and mote remain
the execution authority. The [core-hardening plan](core-hardening-plan.md) now
coordinates storage, recovery, resource limits, authority and upgrade evidence needed
before final local-demo qualification.

## Immediate scope

Use the current macOS workstation, Java 21, a pinned PostgreSQL 17 Docker image and
managed local filesystem storage. Record actual machine, disk and runtime identities
in the installation receipt. Do not assume institutional SSO, cloud storage, a public
domain, external network exposure, approved human data or a dedicated operations team.

Run the application from the packaged JAR distribution. Bind all service/database/
test-provider ports to loopback and choose free ports without taking over another
process. The application runtime uses a constrained database role; migrations are an
explicit separate command. Keep configuration, private credentials, canonical bytes,
database volumes, logs and disposable staging in documented separate locations under
one newly created, explicitly selected local root. A local service instance must not
reuse the repository's test database, previous demonstration roots or another app's
volume. Default roots must be new; never reset unrelated user data.

The local launcher must offer install/check/start/status/stop and a narrowly scoped
demo reset. Restart preserves accepted state; reset requires the exact demo instance
and explicit destructive intent, verifies its ownership marker and refuses arbitrary
paths. No always-running service or startup/login agent is installed implicitly.
An actual retained local instance will be created when L01.2 implements and qualifies
that profile. The O01.1 smoke test used temporary resources and left no demo service
running.

## Demonstration data and identity

Use generated or already permitted repository fixtures only. Seed two fictional labs,
one shared project, one restricted dataset in that project, a second project, a
steward, contributor, reader and approved demo guest. Reuse F06's fixture and ADR
001's organization model; do not create a second authority implementation.

Each account has an explicit local fixture identity through the F04 test-provider
adapter. The UI must identify demo accounts and local authentication accurately.
Exercise the real browser session and repository authority paths; a role-switch
control must never forge an arbitrary principal header or bypass transaction checks.
Local provider configuration is explicitly selected and confined to the demo origin;
institutional configuration must reject demo credentials/issuer settings. Real-provider
qualification F04.4 remains open.

Initial default fixture budget: two labs, two projects, three datasets, at least two
accepted states of one dataset, at most 2,000 files and 1 GiB of generated source
payload. Retain fixture seeds, content hashes and expected outcomes. Include a distinct
optional larger-fixture mode for measuring resource use; its measurements describe
this workstation only. No institution-wide throughput promise follows from a demo.

For **engineering intake**, select approved local native-BIDS staging: an operator
registers a specific fixture source under the demo root, and the browser/API refers
to its opaque source ID. Implement file/digest accounting, interruption/resume and
bounded capacity through W01. Do not expose arbitrary filesystem paths or URLs to
browser callers. Remote upload, real DICOM/XNAT and additional visual analysis are
later scope decisions. SITE01.3 must compare a prospective institution's intake needs
with this implemented baseline and add explicit blocking work for any missing mode.

## The guided story

Target a repeatable 10–15 minute demonstration using ordinary product screens:

1. A steward signs in and opens a lab/project workspace. Show the people, datasets
   and work needing review with recognizable labels.
2. Create a dataset in the project and ingest the registered native-BIDS fixture.
   Show honest operation state, findings and an accepted first release.
3. A researcher finds the permitted dataset, inspects authored/effective metadata
   and retrieves an exact, verified selection or export.
4. A contributor proposes a metadata correction; a steward reviews and accepts it.
   Show both accepted states and retrieve the earlier bytes unchanged.
5. Share exactly the earlier release with the guest. In a separate browser context,
   show that the guest can use that release but cannot discover the restricted
   dataset, current head or private project activity beyond the granted scope.
6. Revoke the share or disable the guest and show current access ending, including
   a stale browser tab. Show the audit/effective-access explanation without sensitive
   token or source leakage.
7. Assign a release-bound review task, add a note and show that a later release
   does not inherit a previous quality verdict.
8. Stop/restart the local service and show the same accepted identities and history.
   A separate reliability rehearsal interrupts intake/delivery and proves safe resume.

If a capability is not implemented, mark it open in the guide; never fake it with a
screen that appears to perform a repository action. QA findings can support the data
stewardship story. Adding a numerical analysis recipe is not on this critical path.

## Acceptance work in mote

EP0 contains one review gate, L01, with six engineering tasks. It consumes the existing
organization, identity, workflow and operations work; these tasks integrate the local
experience rather than rebuilding those features.

| Task | Deliverable | Blocking work |
| --- | --- | --- |
| L01.1 | Local profile, fixture/intake decision, demonstration story and explicit acceptance boundary | F01 |
| L01.2 | Install/check/start/status/stop/reset tools and a retained local instance | L01.1, O01.4 |
| L01.3 | Versioned two-lab seed and local test-provider setup using normal sessions/authority | L01.2, F06.1 |
| L01.4 | Complete the guided repository walkthrough through real product workflows | L01.3, F06.2, W01, W02 |
| L01.5 | Repeatable fresh-install, restart/interruption and browser demonstration qualification | L01.4, H01–H06 |
| L01.6 | Quickstart, presenter/researcher guide and evidence-backed institutional discussion pack | L01.5, H00 |

L01.2 checks new-root installation, port collisions, private files, role separation,
restart persistence and guarded reset. Its receipt includes the exact artifact and
workstation configuration. The durable demo root is reported to the user and no
credentials appear in the report.

L01.3 checks repeatable seeding without duplicate scientific records, all allowed and
denied account views and rejection of demo identity in institutional configuration.
Seed data is explicitly synthetic; privileged seeding is an operator action.

L01.4 binds each step above to its real screen/action, exact expected release/hash,
role and denied action. It records actual screenshots from the running build and
closes usability defects that make the demonstration depend on internal IDs or ad hoc
database edits. Presenter timing is measured; it is not a user-study claim.

L01.5 consumes all six core area reviews, then runs the full browser story from a fresh root three times, plus a warm restart,
interrupted intake/export and a database-unavailable episode. Verify no lost accepted
state, unauthorized disclosure, duplicate acceptance or false completion. Retain
timings, versions, logs, byte assertions and observed limitations. Automated keyboard/
accessibility results do not replace actual screen-reader or institutional user review.

L01.6 consumes H00’s integrated release dossier and ships a short local quickstart; account/data inventory; presenter narrative;
researcher/curator task guide; troubleshooting and teardown; architecture/access/
recovery explanation; and a concise evidence/limitations pack for discussing a pilot.
Every command and screenshot must match the qualified artifact. A clean-environment
rehearsal follows the guide alone and records any manual prerequisite. Preparing this
pack does not authorize contacting an institution or imply its buy-in.

The L01 gate checks all six receipts together and records “local demonstration ready”
only when the actual installation and product journey pass. It does not close SITE01,
F04.4, Q01, Q02, Q03, or production cutover.

## Delivery order and later institutional work

The first completed foundation tranche supplies F01's contracts and O01.1's verified
artifact. T1 now completes O01.2 configuration, O01.3 runtime/migration privileges,
F02.1 reference records, H06.2 historical compatibility and H04.1 workload budgets;
[execution receipts](evidence/core-hardening-t1/receipt.json) retain the evidence.
O01.4 next qualifies actual installation/upgrade and L01.2 supplies workstation tooling.
O03.1's durability contract and F02.2's persisted organization catalog now have
[source-bound evidence](evidence/core-hardening-t2/receipt.json). F03.1 now passes the
38-case pure authority corpus and focused 71-test run; F03.2 now integrates that
model into current transactional permissions. H03.1 and O03.6–O03.8 then establish tested current-capability
recovery, with F02.3 administration and F03.3/F03.4 access checks as prerequisites.
This milestone precedes F04/F05/F06's browser workspace expansion. O03.2/O03.4/O03.5
retain full identity/session recovery; early success cannot close those tasks or the
local-demo gate. W01/W02 complete the repository demonstration journeys.
EP9 also qualifies capacity, integrity repair, backup/current-policy recovery,
withdrawal, measured workload and diagnostics. Six area reviews feed L01.5; H00
then proves one integrated candidate before L01.6 finishes the artifact-matched guide.

Institutional assignments remain deliberately unfilled. SITE01.1 is later outreach/
acceptance work and is not a blocker of the local demo. The former engineering
intake blocker on SITE01.3 is replaced by the local contract in L01.1; institutional
scope acceptance still checks any additional required intake. Real data permission,
real-provider qualification, independent review, actual user/accessibility studies,
actual physical host-loss recovery and operator acceptance remain explicit later
obligations. Local source-isolation restore and separately permissioned test backup/
policy services are engineering prerequisites now; same-host testing does not prove
survival of the workstation or its disk being lost.

F03.2's [transaction evidence](evidence/institutional-pilot/F03.2/receipt.json) covers
current SQL authority, exact grant lineage, offboarding and atomic local policy events.
O03.6.1 qualifies the standalone signed witness. O03.6.2 connects it to catalog
enrollment, synchronous acknowledgement and bounded SQL admission; see
[its evidence](evidence/institutional-pilot/O03.6.2/receipt.json) and
[operator guide](policy-catalog.md). O03.6.3 now qualifies the integrated crash and
fencing behavior in [33 process cases](evidence/institutional-pilot/O03.6.3/receipt.json);
all six unchanged [O03.6 criteria](evidence/institutional-pilot/O03.6/receipt.json) pass.
Next is H03.1 verified backup automation, followed by O03.7/O03.8 restore work. These local
checks do not qualify a recoverable installation, institutional pilot or complete demo.

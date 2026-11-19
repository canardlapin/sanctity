# Sanctity plan of record

19 November 2026. This page is the plan. Where another document in this folder
disagrees about scope, order or priority, this page wins. Live status is on the
tracker: `mote ls --tag mvp`.

## Vision

Sanctity is the institute's research data commons. Every study, live or frozen,
is a versioned BIDS release with a named custodian. A lab shares an exact
release with another lab by name and can take it back. A finished study is
published outward with a licence, a DOI and a Neurobagel record. It is one small
service with standard files at every boundary, so the institute can always reach
and move its own data.

## Order of work

1. **Core.** The technical core is in place and rock solid before any user
   journey begins. We build every engine, tool and deployment capability the
   journeys need, and prove it without people.
2. **Journeys.** Real people then try the four journeys on real data. We begin
   them believing they will pass.

The console screens for the journeys are designed and built alongside the core,
on engine pieces that are already done.

## The core gate

"Rock solid" means this list and nothing else. A new check needs the owner's
yes. The tracker item `CORE` holds the detail; core items carry `phase-core`.

- **Built.** Intake from XNAT, catalog and search, tables with dictionaries,
  whole-release download, publication readiness, the publishing tools, the
  Neurobagel record and node, and the pilot deployment.
- **Journey scripts.** Each journey runs end to end by script on the packaged
  service, with no person and no new screen, green on hosted CI five runs in a
  row.
- **Real data.** A pinned set of public BIDS and DICOM datasets from several
  labs and scanners imports cleanly, or fails with a finding that names the file
  and the fix.
- **Scale.** The frozen workload and a pilot-sized study meet stated budgets.
  Beyond its stated limits the service refuses clearly.
- **Access.** Every read route is tested against every level of access,
  including revoked and expired.
- **Known defects.** The reproduced defects on these paths are fixed.
- **Operations.** Install, upgrade, backup and restore are scripted and pass. A
  restore cannot bring back access that was revoked.

## The four journeys

The MVP is done when real people complete these on an instance inside the
institute network, using one real de-identified study.

| Journey | Done when |
| --- | --- |
| **Bring in** | A study held in the legacy XNAT is pulled, converted to BIDS with standard tools and a per-study mapping, reviewed in the browser and accepted. The release records its origin and carries no XNAT identifiers. |
| **See** | A permitted researcher finds the study by its description and opens one page that shows who holds it, what it contains by participant and data type, and what needs attention, in plain language. |
| **Share** | A steward shares one release, or a subset, with a named person in another lab. The recipient finds it on Home and downloads it. The steward sees everyone with access and why, and revokes it. |
| **Open** | A frozen study passes a readiness checklist, leaves as a validated BIDS package, receives a DOI from an established repository and is found by cohort query in a Neurobagel node. |

Tracker tags: `journey-in`, `journey-see`, `journey-share`, `journey-open`, and
the supporting tracks `journey-run` and `journey-pitch`.

## Decisions taken as defaults

The owner can change any of these.

1. **Open means publish outward.** An established repository hosts the public
   copy and mints the DOI. Sanctity prepares, verifies and records the
   publication and serves no anonymous users. Rehearsals use a sandbox DOI until
   a real dataset is cleared for release.
2. **XNAT stays where it is.** Sanctity pulls from it and converts out of
   process with dcm2niix and a mapping file a curator can read.
3. **Three depths of support.** MRI, behaviour and questionnaire tables are
   checked in depth. EEG, MEG, eye-tracking and other BIDS data types are
   checked by name and metadata. Anything else is stored and labelled.
4. **Start on a host we control.** The first instance runs on a lab server with
   the bundled sign-in service. An institutional machine and single sign-on are
   what the pitch asks for.

## Not in the MVP

Parked behind one tracker gate, with nothing deleted: exchange between
installations; wider recovery, capacity and screen-reader qualification;
signal-level EEG, MEG and eye-tracking readers; REDCap and scanner adapters;
Neurobagel publication control and public federation listing. Processing on
exact releases (QA, preprocessing, analysis) remains an aspiration of the vision
and is not MVP work.

## Working rules

- One plan, one board. `mote ready` lists only MVP work, and
  `mote ls --ready --tag phase-core` lists what comes first.
- No person is asked to try a journey until the core gate has closed and the
  journey scripts pass on the pilot host.
- The journey scripts run on every change. Once journeys begin, people rehearse
  them every Friday, and what breaks is next week's work.
- Every item ends in something a person can do in the browser, or a command an
  operator can run.
- Reviews and audits add notes to existing items. New scope needs the owner's
  yes. A new idea is tagged `later` unless it replaces something in the MVP.

## Target dates

| Date | Gate |
| --- | --- |
| 11 December | The core gate closes. |
| 18 December | The journey screens are built on it. The pilot instance is deployed. |
| 8 January | The journey scripts pass on the pilot host. Journeys with people begin. |
| 22 January | The four journeys have passed with real people. The pitch pack is ready. |

## The owner's list

`mote ls --tag owner`: choose the pilot study and the two labs; get XNAT read
access; secure the host; name the frozen dataset to open, its destination and
licence; book the pitch.

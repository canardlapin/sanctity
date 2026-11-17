# Sanctity product direction: from engineering core to an institutional pilot

Written 17 November 2026. This document sets direction and priorities. It does not
claim that any capability below is implemented or institutionally approved.

## 1. The question being answered

The VP of Research asked whether the institution should use
[Brain-CODE](https://www.braincode.ca/) for institutional collaboration and for
storing multiple data types. This plan answers two questions:

1. What role should Sanctity play relative to Brain-CODE?
2. What must be built for Sanctity to become a demonstrable, multi-modal
   institutional product?

## 2. Brain-CODE in brief

These findings come from public sources only. Most of them are OBI's own
publications, and none is an independent evaluation.

- **Operator.** Brain-CODE is the Ontario Brain Institute's PHIPA-governed
  platform, built and run under contract by Indoc Systems
  ([about](https://www.braincode.ca/content/about-brain-code)). The institution
  has no ownership stake in it and has not used it.
- **Components.** It is an integration of existing tools: REDCap for clinical
  data, LabKey for molecular and genomics data, and SPReD (XNAT) for imaging,
  plus a subject registry and cloud analysis workspaces
  ([about](https://www.braincode.ca/content/about-brain-code)).
- **Data types.** It already accepts clinical, questionnaire, cognitive,
  genomic, MRI, EEG, MEG, eye-tracking, gait and speech data, using OBI common
  data elements. BIDS is adopted only partially, and no public API was found.
- **Governance model.** Brain-CODE is designed for eventual sharing:
  - contributors get a 12-month exclusivity period;
  - controlled access goes through an OBI Data Access Committee and Steering
    Committee;
  - the terms let OBI change or suspend access without notice;
  - hosting costs for non-OBI projects are not published
    ([terms](https://www.braincode.ca/content/platform-terms-use-agreement)).
- **Status.** The platform is active and expanding, with FedDev 2025 funding,
  2026 analytics grants and a link to the federal Vital initiative. The horizon
  for OBI's provincial core funding was not found.

**Conclusion.** Brain-CODE is a mature, Ontario-hosted platform for *sharing and
consortium deposit*. It is not designed as a lab's or an institution's *working
repository*: the system where data is curated while research is under way, with
exact accepted versions, local control of access, and provenance linked to
processing.

## 3. Recommended position: complement Brain-CODE, don't compete with it

Arguing "replace Brain-CODE" is weak: the two systems serve different purposes, and
Brain-CODE already covers more data types than Sanctity does. The stronger case
is this:

> **Sanctity is the institution's governed working repository. Brain-CODE,
> CONP and OpenNeuro are publication and consortium destinations.**
> Labs curate, version, review and share data internally in Sanctity under
> institutional control. When a study is ready, Sanctity exports an exact,
> verified BIDS release for deposit. The institution never loses its
> authoritative copy, its history or its access decisions.

What Sanctity offers that a deposit platform does not:

| Need | Brain-CODE today (public sources) | Sanctity's design |
| --- | --- | --- |
| Institutional control and exit | OBI terms and committees; ownership and exit not stated | Institution-operated; data and access decisions stay local |
| Exact versions | Not described | Immutable accepted releases; every correction is a new reviewed release |
| Standards | Partial BIDS, OBI common data elements | BIDS-native interpretation, findings and exports that round-trip |
| Lab self-service access | Central committees | Labs, projects, stewards, revocable grants, explanations of access |
| Provenance | Manual curation | Corrections, views and derivatives tied to exact inputs |
| Recovery | Not described | Tested backup, closed restore and revocation-preserving recovery |

**Be honest about what Brain-CODE does better.** Brain-CODE is better today at
breadth of data types, an existing PHIPA governance process, ICES linkage,
analysis workspaces and an existing user base. The pitch must not claim parity
on these.

**Questions the VPR should ask OBI:**

- What are the hosting terms and costs for a non-OBI institutional project?
- Who owns the data, and what are the rights to exit and to delete?
- Can the exclusivity and release rules be negotiated?
- Where is the data currently hosted?
- What API and BIDS support does Brain-CODE offer?
- How long is the funding committed?

## 3a. Scope: which jobs of XNAT, LORIS and Brain-CODE Sanctity takes on

Sanctity aims to be a leaner, modern alternative to these systems. It does not
re-implement every feature they have. Each job below is either in scope, handled
by an adapter, or an explicit non-goal.

| Job | XNAT / LORIS / Brain-CODE | Sanctity |
| --- | --- | --- |
| Versioned archive with access control | Core to all three | **Core.** Immutable releases, labs and projects, grants, recovery |
| BIDS as the data model | Bolted on or partial | **Core.** The dataset *is* a BIDS tree |
| Curation, QC findings, corrections | XNAT QC and LORIS imaging QC | **Core.** Findings, reviewed corrections, review state |
| Cross-modal query and frozen selections | LORIS data query tool | **Core** |
| In-browser image viewing | XNAT viewer | **Adopt** NiiVue (NIfTI); add EEG viewing later |
| DICOM from scanner or PACS | XNAT DICOM receiver | **Adapter.** Orthanc as the DICOM node, then dcm2niix conversion and governed intake. Sanctity will not implement DICOM networking itself |
| Clinical and instrument data capture | LORIS instruments; REDCap | **Non-goal.** Keep REDCap and import versioned exports into phenotype |
| Pipelines | XNAT container service; CBRAIN | **Contract.** Containerized BIDS-App processors bound to exact inputs, with results returned as derivatives. No general scheduler |
| Analysis workspaces and compute | Brain-CODE workspaces | **Non-goal.** Use institutional HPC or the Digital Research Alliance; export exact selections |
| Controlled public sharing | Brain-CODE DAC; OpenNeuro | **Export.** Exact BIDS deposits; internal grants handle collaboration |

**The extension model is deliberately small:**

- **Profiles** are compiled modules. Each implements one contract: recognize,
  interpret, assess, produce query facts, declare disclosure fields, export, and
  declare console panels. Each is versioned and tested through the shared
  lifecycle suite. There is no dynamic plugin loader.
- **Adapters** cover importers (DICOM through Orthanc, XNAT, REDCap), blob stores
  (filesystem, S3) and identity (OIDC). Each sits behind an existing capability
  interface.
- **Processors** run out of process: a container receives a frozen input bundle
  and returns outputs. This is the only extension point for third parties, and it
  works in any language.

**The modern baseline:**

- A complete OpenAPI description of the HTTP API; the current one covers only
  part of it.
- The existing console, restructured into typed modules with a small component
  layer.
- An OIDC sign-in, a container image, an S3-compatible store and
  OpenTelemetry-based diagnostics.
- CI on every change.

## 4. Data-type strategy: three tiers, all on one core

The storage and governance core is already modality-neutral. It provides
content-addressed manifests, draft→candidate→release admission, labs and
projects, grants, recovery and provenance. Data types differ only in how much
Sanctity can *interpret*. Each dataset therefore declares a **profile** when it
is created, and the console shows plainly what that profile qualifies.

1. **Qualified BIDS profiles.** Sanctity interprets, assesses and exports these.
   - MRI and behavioural profiles exist today.
   - Next comes EEG at the metadata level: sidecars, `channels.tsv`,
     `electrodes.tsv`, events, and grouping of file families. Binary readers
     come later.
   - After that: MEG, eye-tracking and physiology, following
     [the multimodal plan](multimodal-bids-plan.md).
2. **Phenotype and clinical tables.** BIDS `phenotype/` tables come with JSON
   data dictionaries treated as typed schemas: levels, units, missing codes and
   instrument identity. The facts are queryable at variable level and can be
   disclosed field by field.
   - REDCap remains the capture tool. Sanctity imports versioned exports from
     it; it does not try to replace it.
   - Mapping to OBI common data elements is a later export option.
3. **Archival profile.** This covers genomics, video, audio, raw vendor formats
   and anything else not yet supported.
   - Sanctity preserves the bytes, inventory, checksums, dataset-level
     descriptive metadata, versions, access and recovery.
   - It makes no scientific claims, and the console labels it that way.
   - A collection can later move to a qualified profile by being interpreted
     afresh, leaving its history unchanged.

**Standards stance.** Use BIDS wherever a BIDS specification exists. Use BIDS
phenotype dictionaries for tabular data. Use the archival tier rather than
inventing private schemas. NWB, OMOP or common-data-element mappings are adapters
for later, added when a real collection needs them.

The build plan records these tiers per file as three coverage levels: **Assessed**
(tier 1 with content checks, and tier 2 tables), **Described** (a BIDS data type
whose names and metadata are checked but whose recordings are not opened) and
**Preserved** (tier 3).

## 5. Design and build plan

The [build plan](build-plan.md) holds the detail. It was written after reading the
kernel, the interpreters, the HTTP layer and the console, and it replaces the
earlier outline in this section. In brief:

- **The kernel is already close to modality-neutral.** Its scientific vocabulary
  is BIDS, which fits every BIDS data type. Seven changes make it clean and
  extensible: one interpreter registry, BIDS composed from modalities, graded
  coverage (Preserved, Described, Assessed), tables with dictionaries, a frozen
  identity scheme, upgrades that do not block curation, and datasets that can
  grow.
- **The engine is ahead of the product.** Views, derivatives, export and
  re-assessment exist but are not reachable through the service. Sign-in,
  upload, a participant grid and an image viewer are new.
- **The console is evolved, not replaced.** Its visual design and tests are
  strong; its structure needs splitting into modules.
- **Three stages lead to the pitch**, about six to eight weeks in total, with a
  first internal demo after about three.
- **Scope discipline:** no new hardening or qualification work until the pitch.

## 6. Cost comparison: the Indoc quote versus building in-house

In earlier discussions, Indoc quoted about **$3 million**. Before comparing, confirm
three things about that quote:

- **What it buys:** a custom build, a hosted subscription, or both.
- **Its term:** how many years of hosting and support it covers.
- **Exclusions:** whether storage growth, onboarding new data types and later
  changes cost extra.

For an honest comparison, use the total cost over five years. Count every
recurring cost, not just the cost of building.

The figures below are **planning assumptions to be validated**, not quotes
(CAD per year).

| Cost line | Assumption | Per year |
| --- | --- | --- |
| AI tokens for agentic coding | 2–3 active developers on heavy agentic plans or API use | $30k–80k |
| Interns or co-op students skilled at agentic coding | About 2 full-time equivalents across terms | $100k–140k |
| Paid consultant | Architecture, security and operations review; 200–400 h at $150–200/h | $40k–80k |
| Infrastructure | Server(s), 50–200 TB of managed storage with off-site backup, in a Canadian region or on premises | $20k–60k |
| Research-IT operations partner | 0.2–0.3 FTE for patching, monitoring and restore drills | $30k–45k |
| One-time costs | Security assessment or penetration test, and the privacy-office review | $25k–50k once |
| **Total** | | **≈ $220k–405k per year; ≈ $1.1M–2.1M over five years** |

**How to use this in the pitch:**

- **Savings are real but modest.** They come to roughly one-third to two-thirds
  of the quote, not a tenfold difference. Don't overstate them.
- **Ownership is the stronger argument.** The institution owns the system, its
  data model and its roadmap, and can change it in weeks instead of negotiating a
  change order.
- **The money builds a local asset.** It trains interns in agentic engineering
  and keeps institutional knowledge in-house.
- **Spending is staged and can be stopped.** Fund the eight weeks to the demo
  first: about $40k–70k, which is two months of the annual figure above.
  Continue only if the demo succeeds. A $3M commitment can't be staged this way.
- **Exit risk is low.** Everything exports as standard BIDS, so if the in-house
  effort stops, the data moves to Brain-CODE, CONP or a vendor unchanged.

**Costs that are not on the table:**

- The PI's time as product owner.
- The time research IT spends on onboarding.
- The cost of a single-maintainer gap if key people leave. The mitigation is
  documented runbooks, open standards and a consultant on retainer.

## 7. Risks the pitch must address

- **Sustainability.** Sanctity has one maintainer, working with AI assistance.
  Mitigations:
  - use open standards (BIDS) so data can always be exported;
  - keep the operational footprint small (one Java service, PostgreSQL and
    object storage);
  - document runbooks;
  - ask for a named research-IT partner and an explicit pilot budget.
- **Privacy and security approval.** No institutional review has been done. Give
  research IT and the privacy office a short security overview before the pitch,
  plan the PHIPA review for the pilot, and start with de-identified data.
- **Breadth gap.** Be explicit that the archival tier stores data but does not
  interpret it.
- **Relationship with Brain-CODE.** Present Sanctity as the institution's system
  of record. Brain-CODE remains an option for deposit when a funder or consortium
  requires it.

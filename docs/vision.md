# Sanctity core vision

Restated 13 October 2026. Updated 17 November 2026 to clarify archival use and collaboration across institutions. Updated 19 November 2026 after the owner restated the institute's needs: legacy intake and open publication are now product priorities, and the processing framework is an aspiration that is not part of the MVP. Delivery follows the [plan of record](plan.md).

Sanctity is a BIDS-native research data repository for active studies and long-term
archives. Labs can curate a study as it grows, share a precise selection, or
deposit a finished collection for others to find and use years later. The same
release identifies what was accepted in each case. Through the interface,
researchers can inspect the data and its history. A study held in a legacy system
such as XNAT can be brought in as BIDS, and a finished study can be published
outward as open, FAIR data. QA, visualization, preprocessing and eventually
analysis can build on those exact releases.

## The core promise

A researcher can find the data they are allowed to use, understand its organization,
metadata, history and review status, and retrieve an exact, usable dataset. A curator
can bring data into a project, resolve findings, explain corrections, and publish a
new accepted state without destroying earlier work. A lab can manage collaborators
and responsibilities without depending on a developer or database administrator for
routine actions. An institution can operate, upgrade and recover the service without
silently losing data or restoring permissions that should have ended.

Scientific trust means that stored bytes, metadata explanations, query results,
accepted history and BIDS exports agree. Product trust also means that people can
understand what is happening, who has access, what requires attention, and who is
responsible for it. Both are necessary to deliver the repository.

A collection deposited once is a complete use of Sanctity. Years after the
original team has left, a researcher with permission should be able to find it
by its description, see who now holds custody, inspect the inventory and retrieve
the exact files retained under the archive's policy. This applies equally to a
fully assessed BIDS study and a collection known only by its description and files.

## Product priorities

1. **Dependable data stewardship.** Preserve content, provenance and accepted history;
   support ingestion, discovery, curation, retrieval and tested recovery.
2. **An excellent working interface.** Make common journeys clear and complete,
   including errors, interrupted work, conflicting edits and accessible navigation.
3. **One home for every study and data type.** MRI, EEG, MEG, eye-tracking,
   behaviour and questionnaires sit together in one BIDS-organized study. Data with
   no BIDS specification is stored and labelled as an archive. A study held in a
   legacy system such as XNAT is brought in as BIDS with its origin recorded.
4. **Collaboration across labs and institutions.** A steward can name the release
   or subset another lab may use, explain the grant and end future access. Design
   for collaborators at another institution from the outset. The first deployment
   can serve several labs and approved external guests from one installation.
5. **Open publication and discovery.** A finished study can be made open and FAIR:
   described, licensed, checked for de-identification, published to an established
   repository with a persistent identifier, and discoverable through open-science
   networks such as Neurobagel.

**Aspiration beyond the MVP: a processing framework.** QA, visualization,
preprocessing and eventually analysis build on exact releases, with tools and
their results bound to exact inputs, current authority and normal derivative
admission. This stays part of the vision. It is not MVP work: the derivative and
QC slices already built stay in place, and no new processing work starts before
the MVP has been shown.

Data integrity and coherent BIDS semantics remain core requirements. Additional
scientific profile depth should be driven by collections the product must support.
New numerical methods and general workflow scheduling are later product expansion.

## Working product model

These are proposed implementation boundaries to resolve in the institutional
foundation tranche:

- A **lab** groups people and governance responsibilities.
- A **project** organizes a study's datasets, collaborators, activity and curation work.
- A **dataset** has its own custody and immutable accepted releases; project organization
  does not redefine scientific identity or merge participant identities across studies.
- **Permissions** explicitly govern discovery, reading, contribution, acceptance and
  sharing. A project link or a provenance link does not itself grant access.
- **Project management** initially means dataset organization, assigned curation/review
  tasks, status, notes and activity. Broader scheduling and generic task management
  require separate product decisions.
- **Review and QA status** identifies the release and evidence reviewed. A new release
  must not silently inherit an earlier quality verdict.

## The first institutional journey

A lab steward signs in with institutional identity, creates a project, admits a
supported collection, and assigns collaborators. A researcher discovers the project,
inspects datasets and findings, retrieves a reproducible selection, and records a
review. The steward shares an explicit scope with another lab or approved external
collaborator and can explain and revoke that access. An operator can observe the
service and recover it on replacement infrastructure with current restrictions intact.

The [plan of record](plan.md) defines the next work as four journeys: bring a study
in, see it, share it and publish it. The
[institutional readiness plan](institutional-readiness-plan.md) describes the later
pilot qualification. This vision does not assert that these capabilities are already
implemented or institutionally qualified.

## Relationship to the original documents

The [original October 6 PRD and roadmap](https://chatgpt.com/space/page_516ae99cd8048191a7bcc3e04f069b24)
centered a trustworthy repository, initially delivered through a core and CLI/API,
with a later graphical experience and processing ecosystem. The
[design baseline](design-baseline.md#authority-and-collaboration) also reserved
organizations, memberships and cross-institution collaboration. The present direction
brings everyday interface and collaboration workflows into the core product delivery
sequence and makes project management explicit. The [PRD](prd.md) continues to define
scientific and operational invariants; its existing qualification gates remain open
where their evidence is incomplete.

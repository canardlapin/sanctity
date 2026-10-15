# Sanctity core vision

Restated 13 October 2026 from the original vision and the product owner's clarification.

Sanctity is a BIDS-native research data repository where labs organize projects,
manage and understand their data, control access, and collaborate through an
excellent interface. It connects QA, visualization, preprocessing, and eventually
analysis to versioned data through a small, dependable processing framework.

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

## Product priorities

1. **Dependable data stewardship.** Preserve content, provenance and accepted history;
   support ingestion, discovery, curation, retrieval and tested recovery.
2. **An excellent working interface.** Make common journeys clear and complete,
   including errors, interrupted work, conflicting edits and accessible navigation.
3. **Labs, projects and collaboration.** Organize datasets and responsibilities;
   provide understandable memberships, permissions, sharing and offboarding.
4. **A useful processing framework.** Bind tools and their results to exact inputs,
   current authority and normal derivative admission. QA and visualization are early
   consumers of this framework; additional analysis capabilities follow demonstrated
   needs.

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

The [institutional readiness plan](institutional-readiness-plan.md) assesses current
delivery against that journey and defines the next work. This vision does not assert
that these capabilities are already implemented or institutionally qualified.

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

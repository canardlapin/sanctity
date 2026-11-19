# ADR 005: Discovery shows a release's catalog record

19 November 2026. Version 1. Decided by the product owner. Amends one rule of
[ADR 002](002-institutional-authority.md); everything else there stands.

## Decision

Someone who may discover a release may read that release's catalog record: its kind,
title, description, creators, keywords, data types, licence and identifiers.

ADR 002 said that `Discover` "exposes only a bounded safe dataset/share label and
permitted resource identifier" and "does not expose metadata". From this version it
also exposes the catalog record of the exact release that was made discoverable.

## Why

A share that lets a colleague know a study exists is of little use if all they see is
a label. They cannot tell what the study is, whether it suits their question, or whom
to credit. The catalog record is the study's own description of itself, taken from
`dataset_description.json` and the README, or from `archive_description.json`. It is
the kind of information a published dataset shows on its landing page.

## What discovery still does not show

- File names, paths, sizes, digests or counts.
- Participants, sessions or any per-file metadata.
- Findings, assessments, history or provenance.
- The dataset's current head, or whether any other release exists.
- The project a dataset is placed in, or that project's activity.
- Anything about a private bundle, including its record. A release that one person
  compiled for their own use stays with that person.

Each of the others still needs `ReadMetadata`, as ADR 002 requires.

## Rules

1. The record is read with `Discover` or `ReadMetadata` on that exact release. A
   metadata-only grant (a link-only share) reads it without gaining discovery.
2. Discovery fixed to release R1 shows R1's record. It does not show R2's record, and
   a request for it is answered exactly as for a release that does not exist.
3. Discovery of a dataset covers the record of each of its releases, as it already
   covers their labels.
4. Revoking or expiring the grant ends the read at once, like every other read.
5. A catalog without an organization has no discovery. There the record needs
   `ReadMetadata`, as before.
6. Discovery never opens a private bundle. Its record is read only by the person who
   made it, under the rule that governs every other read of it.
7. The read takes the same locks as any other read of the release before it decides,
   so a revocation that has been acknowledged is always seen.

## Consequences

- A steward who makes a release discoverable shows its description to the recipient.
  The sharing screen must say so before the steward confirms (SHARE1).
- The beginning of the README becomes visible at discovery level. Stewards should
  keep anything sensitive out of the README, as they would for a published dataset.
- An institute catalogue can later be built on this rule: listing a study for every
  signed-in member means letting them discover it.
- Search (SEE3) may match record text for every release the caller may discover.

## Implementation

`PostgresCatalog.describable` decides who may read a record.
`CatalogDiscoverySuite` exercises each rule above through HTTP on PostgreSQL.

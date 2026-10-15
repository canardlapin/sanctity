# Streaming table foundation

`StreamingTsv` and the `ViewCompiler.streams` extension implement the T01 table
mechanism in the [multimodal plan](multimodal-bids-plan.md). Existing small JSON,
behavioural and MRI document projection keeps its separate 4 MiB/32 MiB limits.
This foundation does not yet enable physiological or eye-tracking admission.
Their naming, metadata, recording membership and clock rules remain T02/T03 work.

## Table contract

The decoder accepts strict UTF-8 TSV, either with an exact declared header or
headerless with an ordered column declaration supplied by the caller. It supports
LF/CRLF records, quoted tabs/newlines and doubled quotes. Empty fields must use
`n/a`. It retains at most one row, a field buffer and fixed input/output chunks;
it never accumulates recording rows. Diagnostic coordinates count logical TSV
records and columns, starting at one, including a header when present.

Numeric checks are explicit per column. Decimals retain their lexical spelling;
there is no floating-point conversion, interpolation, replacement of missingness
or clock alignment. The numeric policy accepts signed/exponent notation with at
most 128 characters, precision 128 and absolute scale 308. `n/a` is permitted unless
the caller marks a value required. Nonnegative checks are separately declared.
Projection preserves selected field lexemes, source column order and quoted
content, and emits LF records. It rejects an empty or unknown column mask.

The default envelope is 2 GiB encoded, 2 GiB decoded, 100 million data rows,
256 columns, 64 KiB per unescaped field, 1 MiB per source row including separators,
64 retained diagnostics and five minutes per scan. At least one data row is
required. Callers can use smaller limits. Limit, syntax, schema and compressed
format failures produce explicit refusals. Value assessment counts every invalid
value while retaining only bounded diagnostics; projection fails if any source
value violates the declared schema, including a hidden column.

GZIP decoding validates the RFC 1952 header, optional header CRC, DEFLATE end,
content CRC and decoded size. Optional headers share a 16 KiB budget. The first
profile supports one member; concatenated members and trailing bytes are refused.
This is a repository resource/format boundary, not a claim that BIDS forbids
concatenated GZIP. Generated GZIP has deterministic headers. Operational source
failures propagate as failures; cancellation remains cancellation.

## View lifecycle

A compiler continues to return a normal `ProjectionPlan`, with streamed paths in
its generated-path evidence and a `ProjectionStep` for every output. It additionally
returns `StreamedViewFile` values through `streams`. This method receives a source
reader restricted to exact files in the pinned source manifest. Modalities must
bind their column masks, schema and rule version into the disclosure/compiler
identity and validate their own dependencies before producing these plans.

`ScientificViews` verifies each source's size/digest and rechecks content access,
destination proposal access and applicable source disclosure authority every
64 KiB. Every started source read must reach verified EOF, and every source input
declared by a streamed output must be read completely or supplied as a bounded
source document. Reads must occur in the receipt's inputs. An accidental empty or
partial source read, swallowed read error or incomplete decoder cannot publish a
view. Paths, receipt
steps and generated evidence must agree without collisions before any stream is
written. Generated streams have a 2 GiB per-file and 8 GiB total output budget.

The existing `BlobStore.put` owns staging and cleanup. A failed or cancelled write
does not return a finalized blob. No draft is created until every output and read
has completed. A later failure may leave a completed, unreferenced private blob
for existing retention/collection, as with ordinary document generation; it does
not create an accepted release. Receipts bind the final stored output digests and
exact source blobs. Preparation and acceptance use ordinary repository authority.

## Reproduction

```sh
sbt 'app/testOnly sanctity.app.StreamingTsvSuite sanctity.app.StreamingViewSuite'
python3 scripts/check-streaming-tables.py --workdir target/streaming-resource-run
SANCTITY_INTEGRATION_SUITES=sanctity.postgres.StreamingPersistenceSuite bash scripts/check-postgres.sh
```

The resource probe independently generates 128 MiB and 1 GiB decoded, fixed-width
numeric/missing-value fixtures with Python's standard library. Each is assessed
and projected in a separate warmed JVM with a fixed 128 MiB heap, pre-touch and
four active processors. Python's GZIP
reader checks every projected lexical byte, counts and source/output hashes.
The probe samples JVM RSS every 100 ms and checks incremental peak <=256 MiB and
peak growth <=64 MiB. The measured runtime aggregate is the standalone JVM; there
is no reader child. Build/probe orchestration is explicitly excluded. This does
not substitute for a complete service stress workload or acquired-data review.

Ordinary tests cover decoder boundaries, header/checksum corruption and truncated
tails, strict values, bounded diagnostics, operational failures, timeout and
cancellation. The shared catalog suite exercises a source above 4 MiB, receipt
binding, acceptance/export/re-import, early-termination refusal, collision refusal,
simulated sink failure and live source revocation. Its large TSV is an opaque
stimulus asset so these tests qualify the application protocol without silently
advertising physiological interpretation.

## Retained results

The [commit qualification](evidence/multimodal/commit-qualification.json) records
the isolated multimodal changes over the prior committed baseline. Earlier receipts
below describe the original working tree, which also contained separate pending
MRI, policy and service-packaging work; those changes are excluded from this commit.

The [verification receipt](evidence/multimodal/streaming-verification.json) records
507 passing ordinary tests, 507 passing tests in the isolated source tree, seven
shared PostgreSQL cases and the existing complete crash/restore rehearsal.
All final source fingerprints and generated build identities match. Both the
zero-length and partially consumed input cases refuse publication. Cancellation
is checked after generated bytes have reached disk, with a two-second bound.

The [independent resource receipt](evidence/multimodal/streaming-resource.json)
records the following results on Darwin arm64, Java 21.0.12.1, a fixed pre-touched
128 MiB heap and four active processors:

| Decoded input | Rows | Assessment/projection time | Incremental peak JVM RSS |
| --- | ---: | ---: | ---: |
| 128 MiB | 2,097,152 | 27.07 s | 14.8 MiB |
| 1 GiB | 16,777,216 | 144.07 s | 42.4 MiB |

Every projected byte, source/output hash and final-blob receipt matched the
independent oracle. Both the 256 MiB incremental limit and 64 MiB peak-growth
limit passed. These measurements qualify the standalone mechanism under the
recorded configuration; complete service workloads remain Q02 work.

The [initial resource attempt](evidence/multimodal/streaming-resource-initial.json)
is retained: an elastic 64–160 MiB heap passed byte checks and the incremental
limit but failed the peak-growth target. The final probe fixes heap/processor
configuration and retains per-case measurements even on a final gate failure.
[Other intermediate attempts](evidence/multimodal/streaming-attempts.json) record
the stopped sbt handshake and original PostgreSQL test deadline, rather than
discarding those outcomes.

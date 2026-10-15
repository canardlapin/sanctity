# Frozen engineering workload and budgets

H04.1 establishes targets and independent answers; **no latency, memory or throughput
budget has passed yet**. [engineering-v1.json](workloads/engineering-v1.json) fixes
resources, request mix, concurrency, sampling and numeric limits for Q01.2/H04.2.
It records the actual local Mac/Docker/JDK envelope. PostgreSQL's proposed 2-CPU/2-GiB
container limit, service heap/RSS limits and storage quota are qualification settings,
not a claim that today's service enforces every queue/stream/reservation limit.

The generator imports no Sanctity implementation and queries no service. Both profiles
use seed 104729 and produce inventories, exact content hashes, scientific query answers,
revision export hashes, explicit role/access answers and disclosure canaries. The quick
profile has six datasets and 18 accepted states; stress has 24 datasets, 480 accepted
states and roughly 3.2 GB of initial source bytes. Both include two labs, four projects,
project inheritance, explicit-only restriction, a fixed-R1 guest, metadata-only access,
a cross-project link that confers no access, archival and disabled/outsider denials.

```sh
python3 scripts/generate-engineering-workload.py quick --output target/workload-quick-NEW --materialize
python3 scripts/generate-engineering-workload.py stress --output target/workload-stress-NEW
```

Outputs must be new directories. Without `--materialize`, generation records recipes
and expected hashes without writing the full source corpus. Materialization writes
initial sources, two additional boundary datasets and a standalone slow-stream file.
Boundary sources contain exactly 10,000 and 10,001 entries **including directories**;
the latter must be refused before durable work or managed bytes are retained. Their
flat code files test intake cardinality, while ordinary datasets contain valid synthetic
NIfTI-1 images, 1/8-KiB metadata padding and participant tables. They serve different
purposes and must both be exercised. Slow-stream bytes are excluded from BIDS counts.

Each later revision changes only `dataset_description.json`; unchanged scientific
paths and old bytes must survive. Export hashes cover a canonical sorted JSON mapping
of path to `{sha256,size}`, so archive framing/timestamps cannot disguise content drift.
The selective query is T1w images with Siemens metadata and participant age at least
40; a 90/10 manufacturer skew and hidden matching rows challenge scans and disclosure.
Q01.2 records returned IDs for logical dataset/Rn labels during seeding. It must compare
against these answers, never use returned query results as its expected answer.

The access matrix lists fixture answers rather than implementing another policy engine.
It requires ADR 002's real current authority, including fixed-release scope, independent
metadata-only access, source-evidence restrictions and expiry/disablement precedence.
The guest's R1 listing uses only its safe shared label; a listed action does not reveal
the parent project, latest head or other releases. Sampling uses eligible persona/action
pairs; deliberate denials have separate latency and leakage assertions. Query totals,
inventories, cursor reuse and exports must exclude hidden rows and private canaries.

Minimum warm evidence is three independent runs, each with two minutes warmup and at
least ten minutes steady state, continuing until every operation has 1,000 samples and
each eligible persona/operation pair has 100. Keep every raw sample, refusal and timeout.
Cold evidence uses 20 fresh JVM/database-process cycles. Label it **process-cold**:
the host/VM filesystem cache can remain warm. Filesystem-cold claims require demonstrated
cache isolation and separate results. Record actual resources, free bytes, runtime IDs
and background load every run; a different host needs a separately identified envelope.

Warm p95 targets range from 300 ms for a directory page to 1 s for a query page. Other
mandatory limits include 3 GiB service RSS, four runtime database connections, four
active download streams, eight active control requests, an 8-GiB storage safety reserve
and resource release within two seconds after cancellation. Saturate queues at twice
the configured concurrency; reject overflow within 500 ms and preserve ordinary reads
during slow downloads/imports/exports. Permission checks remain at each admitted chunk
of at most 64 KiB, and external-work authority heartbeat is at most five seconds, as
ADR 002 requires. Timing cannot relax those correctness requirements.

Q01.2 implements calibrated timing/counters and actual service execution; H04.2 measures
and fixes failures. Any corpus, sampling or budget reduction requires a new version,
recorded reason, prior-result comparison and affected requalification in mote. Keep the
old failure. Institutional workloads and agreed targets remain Q01.1/Q01.3.

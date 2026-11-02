# Authority during delivery and processing

Long-lived work uses current authority, even when it began with a valid request.
File delivery and object deposits authorize each chunk of at most 64 KiB. Revocation
stops the next chunk; bytes already admitted to transport buffers cannot be recalled.
Native and durable imports check destination proposal permission. Derivative
collection checks source content permission and destination proposal permission
separately, including generated documents and locally produced outputs.

Local processing and durable imports check authority once per second. The last
successful execution permit lasts at most five seconds, measured from the start of
its authorization lookup. A slow or unavailable lookup cannot extend that permit;
a late result is discarded even if the driver ignores cancellation. Failure cancels
the managed child process. This does not promise real-time scheduling under a
suspended JVM or host, nor recover access to bytes already exported by a user.

A worker heartbeat checks the run's owner, current input use and destination deposit
permission. If permission has been withdrawn, it can record an active run as Failed
without returning run metadata or accepting outputs. A database or witness outage
stops local execution but can leave the durable run state uncertain until the catalog
is reachable. Retained request/workspace evidence is not a successful result.
Restoring permission does not restart a failed run.

Completed-result lookup still requires current input access, including the original
source of a private bundle. Reusing that result for a destination independently
requires destination proposal permission. Acceptance requires its own current grant.
An owner who has lost input access cannot inspect or explicitly interrupt the run;
the execution heartbeat stops it. There is no metadata bypass for cancellation.

The [F03.4 receipt](evidence/institutional-pilot/F03.4/receipt.json) binds controlled
clock, streaming, PostgreSQL and real child-process evidence. These are synthetic
local engineering checks. Institutional identity/session integration and independently
qualified restore remain separate gates.

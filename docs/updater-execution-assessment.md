# Updater execution assessment

Baseline: `8739b207` on `experiment/updater-execution`.
This worktree contains an execution-model prototype, not a performance benchmark.

## Baseline observations

- `V3Updater.run` creates a two-thread scheduled executor. Daily full updates
  and six-minute incremental updates can overlap.
- `runUpdate` captures a mutable local snapshot in the incremental task, while
  the daily task retains the original snapshot loaded by `run`. The daily task
  ignores `runUpdate`'s returned snapshot. Builder serialization alone does not
  establish one owner of the snapshot used for building and publishing.
- `V3Updater` bridges into suspension with several `runBlocking` calls, including
  nested calls during publication. It has a publication mutex, while the builder
  separately locks refresh state across I/O.
- `AdoptRepositoryImpl` starts repository work with `GlobalScope.async`. Those
  tasks are not children of the requesting update, complicating failure and
  cancellation ownership.
- `AdoptReposBuilder.build` visits feature versions sequentially despite its
  parallel-fetch comment. Repository fetching does introduce concurrency.
- `DefaultUpdaterHtmlClient` actually uses Apache `HttpAsyncClient` and adapts
  callbacks to suspension. Suspension is not merely wrapping blocking HTTP.
  Its callback bridge uses `suspendCoroutine`, and retry handling catches
  `Exception`; cancellation and request cancellation deserve separate scrutiny.

## Implemented experiment

- One single-thread scheduled worker owns the release and CDXA snapshots. Both
  daily and incremental jobs read and advance the same snapshots, rather than
  retaining separate copies. Scheduling is configured once, outside update-cycle
  methods. Shutdown interrupts the worker and cancels pending schedules.
- Full and incremental stages suspend directly. Synchronous update cycles and
  scheduled incremental jobs bridge to coroutines at their boundary; publication
  no longer nests `runBlocking` calls. Cancellation/interruption is not converted
  to a successful or unchanged snapshot by the stage-level error handlers.
- Repository fetching uses a caller-owned `coroutineScope` with at most eight
  repository children per feature version (fewer after vendor filtering).
  Failure cancels siblings and the scope waits for cleanup before returning.
- Binary mapping uses caller-owned batches of 16 tasks per release. Batches
  preserve input order and avoid one detached task per asset. This is a local
  batch limit, not a process-wide HTTP concurrency limit.
- Pure upstream and CDXA parsing no longer creates coroutine tasks. The single
  CDXA repository fetch is a direct suspend call, not a detached deferred job.
- Explicit requests are actually enqueued (the previous `String.plus` expression
  discarded them). Queue polling replaces copy-and-clear, preventing concurrent
  additions from being erased. Failed cycles requeue their drained requests.
- Incremental retry cleanup and completed empty/excluded outcomes are staged until
  publication accepts the combined snapshot. Attempted IDs remain pending after a
  later feature fails, publication throws, or a database checksum mismatch rejects
  the proposal, even when no explicit requests were drained in that cycle.
- Existing builder and publication locks, database checksum checks, refresh
  policies and complete-snapshot guards remain. The builder lock is defensive
  for direct callers; scheduled cycles no longer depend on it for ownership.

## Design boundary

Use one orchestration owner for the release snapshot and refresh state. Serialize
full and incremental update transactions through that owner, covering snapshot
selection, fetching, validation, publication and accepted-state advancement.
Keep external database checksum protection: single-process ownership cannot
prevent another process from updating the database.

Keep selection and reconciliation deterministic and synchronous, with I/O passed
in or results supplied separately. Bound parallel I/O within an individual update
and give it a caller-owned lifetime rather than `GlobalScope`.

Retain suspend-based client interfaces and a single-threaded scheduler with
coroutine bridges at synchronous job/cycle boundaries. Do not add a second
coroutine-owned scheduler. Consider a blocking/virtual-thread alternative
only after assessing HTTP and Mongo adapters, timeouts, resource limits and
cancellation; replacing `suspend` with blocking adapters alone is not simplification.

## Trade-offs and remaining work

- Serial updates prevent a long full build from overlapping incremental work;
  measure the resulting freshness delay before selecting this policy.
- Daily and six-minute fixed-delay jobs remain, with a one-minute initial
  incremental delay after the first full cycle. Release and CDXA incremental work
  now share a job, so each waits for the other rather than racing publication.
- New deterministic tests cover latest-snapshot reuse in both directions, no
  duplicate job registration, failed-cycle retention, concurrent requests,
  cancellation, interruption, child-task cleanup, stable mapping order and bounded
  mapping fan-out. Existing snapshot and end-to-end regressions remain in place.
- HTTP callback cancellation is not solved by structured orchestration alone:
  the existing `suspendCoroutine` adapter still needs request-abort integration.
  Tests exercise cooperative child cancellation, not immediate network abort.
- Publishing repositories, statistics and release information is not an atomic
  database transaction. Post-publication failures and cross-process coordination
  still need separate assessment; no distributed-lock guarantee is introduced.
- Pure selection/reconciliation extraction is still a possible subsequent step;
  this prototype changes execution ownership without rewriting refresh policy.
- Validation logs are in ignored `execution-adoptium.log` and
  `execution-adoptopenjdk.log` in this worktree.

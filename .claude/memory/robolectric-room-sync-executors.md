---
name: robolectric-room-sync-executors
description: Robolectric+Room test flakes (Illegal connection pointer / off-thread assertion races) → build the in-memory DB with synchronous executors
type: gotcha
---

Robolectric tests that drive a Room in-memory DB flake two ways, both traced to Room's **async** query/transaction executors (seen 2026-07-05 on PS.1, across `TodayViewModelTest`/`BackupManagerTest`/`ChatRepositoryTest`/`FilteredPapersViewModelTest`/`LibraryViewModelTest`):

1. **`Illegal connection pointer` in the InvalidationTracker refresh** — the tracker's background refresh runnable races `db.close()` in `@After`, touching a closed connection (surfaces as an `IllegalStateException` from `Room Invalidation Tracker Refresh`).
2. **Off-thread assertion races** — a DB-write continuation resumes on Room's Arch-Components background thread (not the test dispatcher), so an assertion right after a `vm.save(...)`/insert reads state before the write's downstream Flow emission lands. `UnconfinedTestDispatcher` + no `advanceUntilIdle()` makes this nondeterministic.

**Fix (root cause, not a sleep):** build the test DB with **synchronous** executors so Room runs queries/txns and the tracker refresh inline on the calling thread — deterministic:

```kotlin
Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java)
    .setQueryExecutor { it.run() }
    .setTransactionExecutor { it.run() }
    .build()
```

**Side effect to watch:** the sync executor changes StateFlow emission *timing* — the initial state may already be populated, so a Turbine test that assumed a separate empty `awaitItem()` before the populated one breaks. Use an `awaitItemMatching { … }` drain (loop `awaitItem()` until the predicate holds) instead of positional `awaitItem()` assumptions.

**The cross-test symptom is the SAME family.** `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` (thrown at
the *next* test's `runTest` startup) is this same InvalidationTracker refresh leaking from a *prior* suite that
lacked the fix — its background refresh throws `Illegal connection pointer` on the `arch_disk_io` thread AFTER
that suite's `db.close()`, and kotlinx-coroutines-test reports it against whatever test starts next (so the
victim passes in isolation but flakes in the full suite). It is NOT a separate coroutine-leak family, and it is
distinct from the JDK-21 teardown flake in [[local-build-jdk17]] (that one WAS `ArxiverApplication.onCreate`
fire-and-forget IO, fixed via a `CoroutineExceptionHandler`).

**Applied to ALL `:app` Room+Robolectric suites (PS.2, 2026-07-05; 19 suites as of PF.2)** — every in-memory `db`
builder carries the two synchronous executors. Any NEW Room+Robolectric suite must add the same two lines.

**These executors REDUCE but do NOT fully ELIMINATE the cross-test leak (corrected 2026-07-06, PF.2).** It
recurred on a PF.2 full-suite run (`FilteredPapersViewModelTest` as the victim; green on a plain re-run — so it's
nondeterministic, not a regression). Root cause the sync executors miss: the leftover invalidation refresh runs on
**`ArchTaskExecutor`'s `arch_disk_io` thread**, which `setQueryExecutor`/`setTransactionExecutor` do **not**
override. The root-cause kill is an `androidx.arch.core:core-testing` **`InstantTaskExecutorRule`** (or an
equivalent `ArchTaskExecutor.getInstance().setDelegate` sync shim) on every Room+Robolectric suite — tracked as a
focused test-infra PR in the ROADMAP backlog (repo-wide, watch the sync-emission-timing side effect above).
**Operationally:** if the full `:app` suite flakes on `UncaughtExceptionsBeforeTest` in CI, re-kick once (loop step
8) — it is this known race, not the diff under review.

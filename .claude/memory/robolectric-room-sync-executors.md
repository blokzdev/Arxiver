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

This fixes the **Room async-executor** family only. A **separate, still-open** family also flakes the `:app`
suite: `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` — an app-startup / prior-test coroutine leaks an
uncaught throw into the *next* test's `runTest` startup (seen 2026-07-05 on `FilteredPapersViewModelTest`;
passes in isolation + on re-run). `ArxiverApplication.onCreate` already has a `CoroutineExceptionHandler` (see
[[local-build-jdk17]]), so that isn't the whole source — the sync-executor fix does NOT address this family.
Re-run clears it; a proper root-cause of the cross-test coroutine bleed is a tracked backlog task. ~14 other
Room+Robolectric suites still use the default async builder — adopt the sync-executor pattern if they flake with
the Room symptoms above.

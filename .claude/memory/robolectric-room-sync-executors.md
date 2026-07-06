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

**These executors REDUCE but do NOT fully ELIMINATE the leak — and it is INTRA-class, not cross-class (corrected
2026-07-06 across PF.2+PF.3).** It recurs nondeterministically (victims seen: `FilteredPapersViewModelTest`,
`BackupManagerTest`; green on a plain re-run — so it's flakiness, not a regression). Two dead ends **empirically
ruled out in PF.3** (don't retry them):
- **`androidx.arch.core:core-testing` `InstantTaskExecutorRule` does NOT fix it.** Applied to ALL 36 Room+Robolectric
  suites, the full `:app` suite still flaked (`FilteredPapersViewModelTest`, run 3 of 3). Room 2.7's
  `TriggerBasedInvalidationTracker.notifyInvalidation` refresh is a **coroutine** (stack: `useConnection` →
  `SupportSQLitePooledConnection.transaction`), NOT the legacy `ArchTaskExecutor` LiveData path the rule redirects.
  The `arch_disk_io` thread name is incidental. Reverted the whole experiment.
- **`forkEvery(1)` is ALREADY set** (`app/build.gradle.kts` `testOptions.unitTests`), so each test *class* already
  gets its own JVM. The `UncaughtExceptionsBeforeTest` therefore leaks **method→method within ONE class** (a prior
  test's `db.close()` leaves a pending refresh coroutine that fires at the next method's `runTest` startup), which
  is why class-level isolation + per-class rules don't help.
**THE ACTUAL FIX (PF.3, 2026-07-06): `sqliteMode=NATIVE`.** The `Illegal connection pointer` is a
**`ShadowLegacySQLiteConnection`** artifact — Robolectric's default LEGACY Java SQLite shadow tracks per-thread
connection pointers, and the post-close invalidation refresh coroutine hits a freed pointer → throws. Robolectric's
**NATIVE** SQLite (real SQLite, no shadow pointer bookkeeping) makes the race un-manifestable. Fix = a
`robolectric.properties` with `sqliteMode=NATIVE` in the test resources of every module with Room+Robolectric tests
(committed: `app/src/test/resources/robolectric.properties`, `core/database/src/test/resources/robolectric.properties`).
**Verified: the `:app` suite went 3/3 green after ~5 consecutive flakes without it.** NATIVE is closer to real
SQLite (behavior is *more* correct, not less) — the full build stayed green, no test regressed. The sync executors
stay (harmless, deterministic emission timing); they're no longer load-bearing for the flake. If a NEW module adds
Room+Robolectric tests, add the same `robolectric.properties`. Dead-ends (do NOT retry): InstantTaskExecutorRule
(refresh isn't on the ArchTaskExecutor path); `forkEvery(1)` is already set so isolation is per-class only.

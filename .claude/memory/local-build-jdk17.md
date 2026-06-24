---
name: local-build-jdk17
description: Run local Gradle builds on JDK 17 (CI's JVM); JDK 21 causes a Windows daemon file-lock and a coroutine-test flake
type: gotcha
---

Local `./gradlew build` must run on **JDK 17** — the same JVM CI uses (`.github/workflows/ci.yml` → temurin 17). Two failure modes appear otherwise (both seen 2026-06-23 on a Windows dev box whose default `JAVA_HOME` was JDK 21):

1. **Windows daemon file-lock.** Building once under JDK 21 then again under JDK 17 (or vice-versa) leaves two Gradle daemons that fight over `app/build/.../classes.jar` → `FileSystemException: The process cannot access the file because it is being used by another process`. One daemon/JVM only.
2. **A coroutine-test flake on fast multi-core machines** surfaced under JDK 21's scheduling: `ArxiverApplication.onCreate`'s fire-and-forget IO coroutines raced Robolectric DB teardown and leaked an uncaught `IllegalStateException` into the next `runTest` (`UncaughtExceptionsBeforeTest`) — flaked `RagIndexerTest`/`ChatRepositoryTest`/`OrganizeViewModelTest`/`FilteredPapersViewModelTest`. That root cause is now fixed in code (a `CoroutineExceptionHandler`), but pin 17 anyway to match CI.

Fix on this machine: `org.gradle.java.home` is pinned to the foojay-provisioned JDK 17 (`~/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2`) in the **machine-local** `~/.gradle/gradle.properties` (not in the repo). A fresh machine needs the same pin, or a JDK 17 discoverable by the foojay toolchain resolver (the `:core:model`/`:core:common` `jvmToolchain(17)` already require one). See [[repo-landing-process]].

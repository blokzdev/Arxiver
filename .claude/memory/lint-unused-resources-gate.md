---
name: lint-unused-resources-gate
description: Android lint fails the build on unused string/resources; add a strings.xml key only with its consumer
type: gotcha
---

`./gradlew build` runs `:app:lintDebug` with `UnusedResources` at **error** level, so adding a
`strings.xml` key (or any resource) before the code that references it fails the gate
("The resource R.string.X appears to be unused"). Compile + unit tests + ktlint can all be green and
the build still fails here. Add each string in the same subphase/commit as the composable that uses
it — don't pre-stage forward-looking strings. (Hit 2026-06-16 staging UX2.5/UX2.6 strings during the
UX2.4 commit.)

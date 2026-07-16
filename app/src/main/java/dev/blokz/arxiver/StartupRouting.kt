package dev.blokz.arxiver

import dev.blokz.arxiver.core.model.ArxivId

/**
 * The first-run routing fork (P-Prove PP.4), extracted as a pure function so it is unit-testable without the flaky
 * `MainActivity` Compose+Hilt Activity test the repo deliberately avoids. Onboarding shows only on a genuine fresh
 * install with no deep link — a deep link (VIEW/SEND of an arXiv id, or the PA.2 widget's paper tap) always jumps
 * straight to the paper, even before the user has onboarded.
 */
internal fun shouldStartOnboarding(
    onboarded: Boolean,
    deepLinkPaperId: ArxivId?,
    deepLinkStorageId: String? = null,
): Boolean = !onboarded && deepLinkPaperId == null && deepLinkStorageId == null

package dev.blokz.arxiver.core.pdf

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PFT.5.4 — the two-sided guard. ACCEPT fixtures pin the false-reject floor (real prose, incl. a math-heavy
 * paper — arXiv's core, which a naive symbol-counting gate would wrongly drop); REJECT fixtures pin the
 * clearly-broken extractions the gate must catch. Column-interleave is deliberately NOT asserted here — it's
 * mitigated by `sortByPosition = false` + the device A/B, per the gate's documented limitation.
 */
class PdfTextQualityGateTest {
    // --- ACCEPT: real, readable prose must pass (false-reject floor) ---

    @Test
    fun `accepts clean English prose`() {
        val prose =
            "We propose a new method for training large language models on limited data. The approach " +
                "combines a contrastive objective with a lightweight adapter that is inserted between the frozen " +
                "layers of the network. In our experiments this method improves accuracy over the baseline on " +
                "three benchmarks, and it does so without increasing the number of parameters that must be " +
                "updated during fine-tuning. We also show that the gains hold when the model is evaluated on " +
                "out-of-distribution examples, which suggests the learned representations are more robust."
        assertTrue(PdfTextQualityGate.isAcceptable(prose))
    }

    @Test
    fun `accepts a math-heavy paper's prose (arXiv core, symbols and all)`() {
        val math =
            "Let f be a function from X to Y and let theta denote the parameters of the model. We define the " +
                "loss as L = sum over i of ( y_i - f( x_i ) )^2 + lambda * || theta ||. The gradient of L with " +
                "respect to theta is computed by back-propagation. We minimize this objective using stochastic " +
                "gradient descent with a learning rate of 0.001, so that theta = theta - eta * grad. In practice " +
                "we find that this converges after about 100 epochs when the batch size is 32, and the resulting " +
                "estimator is consistent under the assumptions stated in Section 3."
        assertTrue(PdfTextQualityGate.isAcceptable(math))
    }

    @Test
    fun `trims a trailing bibliography before scoring`() {
        val body =
            "We study the behaviour of the model under distribution shift and find that the accuracy of the " +
                "method degrades gracefully as the shift increases, which is consistent with the theory that we " +
                "developed in the previous section and with the results reported by earlier work on this problem. " +
                "We further observe that the effect is stronger when the training set is small, and we argue that " +
                "this is because the model has fewer examples from which to learn the invariances that matter for " +
                "the task at hand, a point we return to in the discussion. " +
                "References Smith J 2020 Deep Nets doi 10.1 Jones A 2021 More Nets doi 10.2 Lee K 2019 Even More " +
                "Nets doi 10.3 Kim S 2022 Yet More Nets doi 10.4 Park H 2023 Still More Nets doi 10.5"
        // The name/DOI soup after "References" would drag the ratio down; trimming keeps the real body scoring.
        assertTrue(PdfTextQualityGate.isAcceptable(body))
        assertFalse("References" in PdfTextQualityGate.trimBibliography(body))
    }

    // --- REJECT: the clearly-broken extractions ---

    @Test
    fun `rejects scrambled glyph runs (real letters, no real words)`() {
        val fake = listOf("xkqz", "wprmb", "tljnv", "qgfhd", "zmbkw", "vhtxp", "brqmn", "dklfz")
        val scrambled = (1..10).joinToString(" ") { fake.joinToString(" ") } // 80 non-word tokens, 0 function words
        assertFalse(PdfTextQualityGate.isAcceptable(scrambled))
    }

    @Test
    fun `rejects CID no-space run-together`() {
        val runTogether =
            "Weproposeanewmethodfortraininglargelanguagemodelsonlimiteddatatheapproachcombinesacontrastive" +
                "objectivewithalightweightadapterinsertedbetweenthefrozenlayersofthenetwork"
        assertFalse(PdfTextQualityGate.isAcceptable(runTogether))
    }

    @Test
    fun `rejects a symbol and number dump`() {
        val symbols = (1..80).joinToString(" ") { listOf("=", "+", "3.14", "2.71", "∑", "∫", "≤", "≥")[it % 8] }
        assertFalse(PdfTextQualityGate.isAcceptable(symbols))
    }

    @Test
    fun `rejects scanned or empty (too few words)`() {
        assertFalse(PdfTextQualityGate.isAcceptable(""))
        assertFalse(PdfTextQualityGate.isAcceptable("Figure 1 Table 2 Section 3"))
    }
}

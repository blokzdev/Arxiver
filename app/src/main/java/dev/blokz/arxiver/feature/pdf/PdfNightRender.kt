package dev.blokz.arxiver.feature.pdf

/**
 * The reader's night-mode colour transform (P-Reader2 PR.UX.1).
 *
 * A plain negation (`out = 255 − in` per channel) inverts *hue* as well as lightness — a red curve turns
 * cyan, a blue plot orange, a green trace magenta — so figures stop making sense the moment night mode is on.
 * This is instead a **smart invert**: `hueRotate(180°) ∘ invert`, which inverts lightness while *preserving
 * hue*, so plots and coloured diagrams stay recognisable. The black/white extremes are softened (a white page
 * lands on a soft dark ~18 rather than pure black; black ink on a soft light ~230 rather than a blinding pure
 * white) for OLED reading comfort.
 *
 * Derivation: a luminance-preserving hue rotation fixes the grey axis (its rows sum to 1), so composing it
 * with the affine invert collapses to a single linear part `−A·H(180°)` plus a flat per-channel offset. `A`
 * (slope) and the offset are chosen so a grey value `k` maps linearly onto `[230 (k=0) … 18 (k=255)]`; the
 * grey axis therefore inverts smoothly through a near-fixed mid-grey pivot while colours keep their family.
 * `H(180°)` uses `cos = −1, sin = 0`, so `H[i][j] = 2·luma[j] − (i == j ? 1 : 0)`.
 */
internal object PdfNightRender {
    // Rec.709 luma weights — the Android/Compose ColorMatrix hue-rotation convention.
    private const val LR = 0.213f
    private const val LG = 0.715f
    private const val LB = 0.072f

    /** Softening slope: grey k → offset − A·k, i.e. white(255) → 18, black(0) → 230. Keeps both rails off 0/255. */
    private const val A = 0.831f
    private const val OFFSET = 230f

    /**
     * The 4×5 row-major colour matrix (Compose/Android `ColorMatrix` layout: four rows of
     * `[r, g, b, a, offset]`, offset on the 0–255 scale). Linear part is `−A·H(180°)`; alpha is passed through.
     */
    val matrix: FloatArray =
        floatArrayOf(
            -A * (2 * LR - 1), -A * (2 * LG), -A * (2 * LB), 0f, OFFSET,
            -A * (2 * LR), -A * (2 * LG - 1), -A * (2 * LB), 0f, OFFSET,
            -A * (2 * LR), -A * (2 * LG), -A * (2 * LB - 1), 0f, OFFSET,
            0f, 0f, 0f, 1f, 0f,
        )

    /**
     * Pure application of [matrix] to an sRGB triple, clamped to `[0,255]`. Mirrors exactly what the GPU
     * `ColorFilter` does per pixel, so the unit test can assert the transform's behaviour without a device.
     */
    fun apply(
        r: Int,
        g: Int,
        b: Int,
    ): Triple<Int, Int, Int> {
        fun channel(row: Int): Int =
            (matrix[row] * r + matrix[row + 1] * g + matrix[row + 2] * b + matrix[row + 4])
                .toInt()
                .coerceIn(0, 255)
        return Triple(channel(0), channel(5), channel(10))
    }
}

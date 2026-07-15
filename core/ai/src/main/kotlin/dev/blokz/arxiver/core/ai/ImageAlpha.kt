package dev.blokz.arxiver.core.ai

/**
 * Detects, from raw image bytes, whether a raster figure carries *transparency* (P-Reader2 HR-FMT.4).
 *
 * This gates the dark-mode figure matte: a transparent PNG diagram (black axes/ink on a see-through
 * background — how matplotlib exports with `transparent=True`) vanishes into a dark reader page, but an
 * opaque JPEG photo must NOT get boxed. So the matte is applied only to figures this reports transparent.
 *
 * Pure header inspection (no `android.graphics.Bitmap` decode) so it stays a `:core:ai` unit-testable function
 * and costs nothing per image. Conservative by construction: anything it can't positively confirm as
 * transparent (JPEG always; a malformed/other header) reports `false` — a missed matte is cosmetic, a wrong
 * matte on a photo is not.
 */
object ImageAlpha {
    /**
     * @param mimeSubtype one of the [HtmlImageFetcher] raster subtypes ("png"/"jpeg"/"gif"/"webp").
     * @param bytes the raw (pre-base64) image bytes.
     */
    fun hasAlpha(
        mimeSubtype: String,
        bytes: ByteArray,
    ): Boolean =
        when (mimeSubtype.lowercase()) {
            "png" -> pngHasAlpha(bytes)
            "gif" -> gifHasAlpha(bytes)
            "webp" -> webpHasAlpha(bytes)
            else -> false // jpeg (and anything unknown) is never transparent
        }

    /**
     * PNG: the IHDR colour-type byte (offset 25) is 4 (grey+alpha) or 6 (RGBA) → a real alpha channel;
     * bit 2 (`& 4`) is the alpha flag for those. Palette/grey/RGB PNGs express transparency via a `tRNS`
     * chunk instead, so also treat a `tRNS` chunk as transparent.
     */
    private fun pngHasAlpha(bytes: ByteArray): Boolean {
        if (bytes.size < 26) return false
        // 8-byte signature + "IHDR" chunk; colour type is the 26th byte overall (index 25).
        val colorType = bytes[25].toInt() and 0xFF
        if (colorType and 4 != 0) return true
        return containsAscii(bytes, "tRNS")
    }

    /**
     * GIF: transparency lives in a Graphic Control Extension — introducer `0x21 0xF9`, block-size `0x04`,
     * then a packed byte whose low bit is the "transparent colour" flag. Scan for that signature.
     */
    private fun gifHasAlpha(bytes: ByteArray): Boolean {
        var i = 0
        while (i + 3 < bytes.size) {
            if (bytes[i].toInt() and 0xFF == 0x21 &&
                bytes[i + 1].toInt() and 0xFF == 0xF9 &&
                bytes[i + 2].toInt() and 0xFF == 0x04 &&
                bytes[i + 3].toInt() and 0x01 != 0
            ) {
                return true
            }
            i++
        }
        return false
    }

    /**
     * WebP: extended container `VP8X` carries an alpha flag (bit 4 of the flags byte at offset 20), and any
     * webp with alpha data has an `ALPH` chunk. Lossless `VP8L` encodes an `alpha_is_used` bit (bit 28 of the
     * little-endian 32-bit header word after the `0x2F` signature at offset 20). Check all three.
     */
    private fun webpHasAlpha(bytes: ByteArray): Boolean {
        if (bytes.size < 16) return false
        if (!containsAsciiAt(bytes, "RIFF", 0) || !containsAsciiAt(bytes, "WEBP", 8)) return false
        return when {
            containsAsciiAt(bytes, "VP8X", 12) -> bytes.size > 20 && (bytes[20].toInt() and 0x10) != 0
            containsAsciiAt(bytes, "VP8L", 12) -> vp8lAlpha(bytes)
            else -> containsAscii(bytes, "ALPH")
        }
    }

    private fun vp8lAlpha(bytes: ByteArray): Boolean {
        // "VP8L"(12..15) + chunk size(16..19) + 0x2F signature(20) + packed 32-bit LE header(21..24).
        if (bytes.size < 25 || (bytes[20].toInt() and 0xFF) != 0x2F) return false
        val packed =
            (bytes[21].toInt() and 0xFF) or
                ((bytes[22].toInt() and 0xFF) shl 8) or
                ((bytes[23].toInt() and 0xFF) shl 16) or
                ((bytes[24].toInt() and 0xFF) shl 24)
        return (packed ushr 28) and 1 == 1
    }

    private fun containsAsciiAt(
        bytes: ByteArray,
        token: String,
        at: Int,
    ): Boolean {
        if (at + token.length > bytes.size) return false
        for (k in token.indices) if (bytes[at + k].toInt() and 0xFF != token[k].code) return false
        return true
    }

    private fun containsAscii(
        bytes: ByteArray,
        token: String,
    ): Boolean {
        var i = 0
        while (i + token.length <= bytes.size) {
            if (containsAsciiAt(bytes, token, i)) return true
            i++
        }
        return false
    }
}

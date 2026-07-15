package dev.blokz.arxiver.core.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HR-FMT.4 — transparency detection from raster headers (drives the dark-mode figure matte). The contract:
 * positively confirm transparency for the encodings that carry it, and default to opaque (no matte) otherwise
 * — never box a photo.
 */
class ImageAlphaTest {
    /**
     * Minimal PNG through the IHDR colour-type byte, optionally with extra chunk bytes appended. Layout:
     * 8-byte signature, IHDR length (0x0D), "IHDR", width(4), height(4), bit-depth(1, index 24),
     * colour-type(1, index 25).
     */
    private fun png(
        colorType: Int,
        extra: ByteArray = ByteArray(0),
    ): ByteArray {
        val head =
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0, 0, 0, 0x0D,
                'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
                0, 0, 0, 4,
                0, 0, 0, 4,
                8,
                colorType.toByte(),
            )
        return head + extra
    }

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    @Test
    fun `RGBA and grey+alpha PNGs are transparent`() {
        assertTrue(ImageAlpha.hasAlpha("png", png(colorType = 6))) // RGBA
        assertTrue(ImageAlpha.hasAlpha("png", png(colorType = 4))) // grey + alpha
    }

    @Test
    fun `an RGB PNG with no tRNS is opaque, but one WITH a tRNS chunk is transparent`() {
        assertFalse(ImageAlpha.hasAlpha("png", png(colorType = 2)))
        assertTrue(ImageAlpha.hasAlpha("png", png(colorType = 3) + ascii("tRNS")))
    }

    @Test
    fun `JPEG is never transparent`() {
        assertFalse(ImageAlpha.hasAlpha("jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)))
    }

    @Test
    fun `a GIF with a Graphic Control Extension transparency flag is transparent`() {
        val on = ascii("GIF89a") + byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x01, 0, 0, 0, 0)
        val off = ascii("GIF89a") + byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x00, 0, 0, 0, 0)
        assertTrue(ImageAlpha.hasAlpha("gif", on))
        assertFalse(ImageAlpha.hasAlpha("gif", off))
    }

    @Test
    fun `a VP8X WebP with the alpha flag, and any WebP with an ALPH chunk, are transparent`() {
        val vp8xAlpha = ascii("RIFF") + ByteArray(4) + ascii("WEBP") + ascii("VP8X") + ByteArray(4) + byteArrayOf(0x10)
        val vp8xOpaque = ascii("RIFF") + ByteArray(4) + ascii("WEBP") + ascii("VP8X") + ByteArray(4) + byteArrayOf(0x00)
        val lossyWithAlph = ascii("RIFF") + ByteArray(4) + ascii("WEBP") + ascii("VP8 ") + ByteArray(6) + ascii("ALPH")
        assertTrue(ImageAlpha.hasAlpha("webp", vp8xAlpha))
        assertFalse(ImageAlpha.hasAlpha("webp", vp8xOpaque))
        assertTrue(ImageAlpha.hasAlpha("webp", lossyWithAlph))
    }

    @Test
    fun `a truncated or unknown blob is opaque, never a false matte`() {
        assertFalse(ImageAlpha.hasAlpha("png", byteArrayOf(0x89.toByte(), 0x50)))
        assertFalse(ImageAlpha.hasAlpha("webp", ascii("RIFF")))
        assertFalse(ImageAlpha.hasAlpha("bmp", ByteArray(64)))
    }
}

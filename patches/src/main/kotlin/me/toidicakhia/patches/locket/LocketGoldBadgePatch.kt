package me.toidicakhia.patches.locket

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import java.security.MessageDigest
import me.toidicakhia.patches.shared.Constants.LOCKET_COMPATIBILITY

private const val PATCH_NAME = "Keep Gold badge toggle state local"

/** SHA-1 of the 1.247.3 Hermes bundle without its 20-byte footer. */
private const val ORIGINAL_BUNDLE_SHA1 = "762558786869a28f3bcb885c283f312a4cb9067e"

private const val FUNCTION_HEADER_OFFSET = 339776
private const val SYNC_EFFECT_OFFSET = 8705417
private const val SYNC_EFFECT_SIZE = 107
private const val FILE_LENGTH_OFFSET = 32
private const val HERMES_FOOTER_SIZE = 20

/** Original 16-byte small header of `doesUserHaveGoldBadge()`. */
private const val ORIGINAL_GETTER_HEADER =
    "4b0360041000555bcc9aa10400000005"

/** Header redirected to the relocated getter: 44 bytes, 10 registers, 4 cache slots. */
private const val PATCHED_GETTER_HEADER =
    "b8e9a1042c00555bcc9aa11400040405"

/** The server-sync effect closure in `useCustomization()`. */
private const val SYNC_EFFECT_PATTERN =
    "2904002e0104037600bc6001002901012e02010837030201aab62e05040077020e060502760290090637020502bb6453060300022e050403bc2b06052e03010e030240020678a5400205cba42e050404400205c4842e040401400204e6b153020300022e0101115c015c00"

/** Same effect, writing the toggle to `userPreferencesMMKV` via `setBoolean`. */
private const val PATCHED_SYNC_EFFECT =
    "300037010001aa5e6f02a105000053030100023704030235423705040325d37306253f2907002e08070354090500060876095c09"

/** Relocated `doesUserHaveGoldBadge()`: reads `gold_badge_enabled` from MMKV. */
private const val PATCHED_GETTER =
    "300037010001aa5e6f02a1050000530301000237040302354237050403c56d7306253f790753080500065c08"

/**
 * Keeps the "Locket Gold badge" toggle in Locket's own MMKV preferences without
 * shipping a rebuilt bundle:
 *
 * - `doesUserHaveGoldBadge()` is relocated to a short function appended before
 *   the Hermes footer. It reads `gold_badge_enabled` from `userPreferencesMMKV`
 *   instead of the server-provided profile badge, so the badge is still drawn
 *   on the next launch.
 * - The `useCustomization()` effect that used to write the toggle to Locket's
 *   servers is overwritten in place with a write of the same value to MMKV.
 *
 * The original bundle is verified by its Hermes SHA-1 footer and by both
 * patched byte patterns before anything is written, so other Locket builds fail
 * with a clear error instead of producing a broken APK.
 */
@Suppress("unused")
val locketGoldBadgePatch = resourcePatch(
    name = PATCH_NAME,
    description = "Makes the 'Locket Gold badge' toggle work without a subscription. " +
        "The last on/off position is saved locally and restored after a restart, " +
        "instead of being written to Locket's servers, which reject badge changes " +
        "from unsubscribed accounts. Client-side only: the badge is not stored on " +
        "Locket's servers.",
    default = true
) {
    compatibleWith(LOCKET_COMPATIBILITY)

    execute {
        val bundle = get(HERMES_BUNDLE_PATH)
        val bytecode = bundle.readBytes()
        bytecode.requireHermes96(HERMES_BUNDLE_PATH)

        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(bytecode, 0, bytecode.size - HERMES_FOOTER_SIZE)
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != ORIGINAL_BUNDLE_SHA1) {
            throw PatchException(
                "Unsupported $HERMES_BUNDLE_PATH (sha1=$actual); expected the " +
                    "Locket 1.247.3 bundle."
            )
        }

        val originalHeader = ORIGINAL_GETTER_HEADER.hexToByteArray()
        if (!bytecode.copyOfRange(FUNCTION_HEADER_OFFSET, FUNCTION_HEADER_OFFSET + originalHeader.size)
                .contentEquals(originalHeader)
        ) {
            throw PatchException("Could not locate doesUserHaveGoldBadge() in $HERMES_BUNDLE_PATH.")
        }

        val originalEffect = SYNC_EFFECT_PATTERN.hexToByteArray()
        if (!bytecode.copyOfRange(SYNC_EFFECT_OFFSET, SYNC_EFFECT_OFFSET + SYNC_EFFECT_SIZE)
                .contentEquals(originalEffect)
        ) {
            throw PatchException("Could not locate the Gold badge sync effect in $HERMES_BUNDLE_PATH.")
        }

        val getter = PATCHED_GETTER.hexToByteArray()
        val effect = PATCHED_SYNC_EFFECT.hexToByteArray()
        val footerStart = bytecode.size - HERMES_FOOTER_SIZE
        val patched = bytecode.copyOf(bytecode.size + getter.size)

        // Move the footer to the end and append the relocated getter in its place.
        bytecode.copyInto(patched, 0, 0, footerStart)
        bytecode.copyInto(patched, patched.size - HERMES_FOOTER_SIZE, footerStart)
        getter.copyInto(patched, footerStart)

        PATCHED_GETTER_HEADER.hexToByteArray().copyInto(patched, FUNCTION_HEADER_OFFSET)
        effect.copyInto(patched, SYNC_EFFECT_OFFSET)
        patched.fill(0, SYNC_EFFECT_OFFSET + effect.size, SYNC_EFFECT_OFFSET + SYNC_EFFECT_SIZE)

        val newLength = patched.size
        patched[FILE_LENGTH_OFFSET] = newLength.toByte()
        patched[FILE_LENGTH_OFFSET + 1] = (newLength shr 8).toByte()
        patched[FILE_LENGTH_OFFSET + 2] = (newLength shr 16).toByte()
        patched[FILE_LENGTH_OFFSET + 3] = (newLength shr 24).toByte()

        digest.reset()
        digest.update(patched, 0, patched.size - HERMES_FOOTER_SIZE)
        digest.digest().copyInto(patched, patched.size - HERMES_FOOTER_SIZE)

        bundle.writeBytes(patched)
    }
}

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

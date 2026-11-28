package me.toidicakhia.patches.locket

import app.morphe.patcher.patch.resourcePatch
import me.toidicakhia.patches.shared.Constants.LOCKET_COMPATIBILITY

private const val PATCH_NAME = "Enable Locket Gold badge"

/**
 * Hermes bytecode of doesUserHaveGoldBadge() in Locket 1.247.3 (version code 636).
 *
 * The function is the single source of truth for the "Locket Gold badge" setting:
 * useCustomization() derives `goldBadgeEnabled` from it, renderAvatarBadge() uses
 * it to draw the badge, and the settings toggle writes the result back to the
 * server through changeProfileInfo(). Because the server only accepts the badge
 * for a real Gold subscriber, the toggle can never stay on in a patched app.
 *
 * Compiled body:
 *
 *   GetEnvironment r0
 *   LoadFromEnvironment r1, r0, LOCKET_GOLD_BADGE
 *   LoadParam r0, 1
 *   StrictEq r0, r1, r0
 *   Ret r0
 *
 * Verified against the exact 1.247.3 bundle; other builds compile this function
 * to different bytes and are intentionally unsupported by the compatibility.
 */
private val DOES_USER_HAVE_GOLD_BADGE =
    "2900002e0100046c00010f0000015c00".hexToByteArray()

@Suppress("unused")
val locketGoldBadgePatch = resourcePatch(
    name = PATCH_NAME,
    description = "Turns on the 'Locket Gold badge' setting and draws the Gold badge on " +
        "avatars, without needing Locket's servers to accept a badge change. " +
        "Client-side only: the badge is not stored on Locket's servers.",
    default = true
) {
    compatibleWith(LOCKET_COMPATIBILITY)

    execute {
        val bundle = get(HERMES_BUNDLE_PATH)
        val bytecode = bundle.readBytes()
        bytecode.requireHermes96(HERMES_BUNDLE_PATH)

        bytecode.stubHermesBoolGetter(
            DOES_USER_HAVE_GOLD_BADGE,
            value = true,
            label = "doesUserHaveGoldBadge"
        )
        bundle.writeBytes(bytecode)
    }
}

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

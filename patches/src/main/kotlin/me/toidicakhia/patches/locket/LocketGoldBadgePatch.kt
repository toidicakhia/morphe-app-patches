package me.toidicakhia.patches.locket

import app.morphe.patcher.patch.resourcePatch
import me.toidicakhia.patches.shared.Constants.LOCKET_COMPATIBILITY

private const val PATCH_NAME = "Keep Gold badge toggle state local"

/**
 * Hermes bytecode of the effect closure in useCustomization() (Locket 1.247.3,
 * version code 636) that pushes the local "Locket Gold badge" state back to
 * Locket's servers:
 *
 *   if (goldBadgeEnabled == null) return
 *   if (doesUserHaveGoldBadge(currentUser?.badge) !== goldBadgeEnabled) {
 *       updateGoldBadge({ oldValue, newValue, setGoldBadgeEnabled, updateCurrentUser })
 *   }
 *
 * updateGoldBadge() calls changeProfileInfo({ badge: ... }) and then
 * updateCurrentUser(). Without a real subscription Locket rejects the badge
 * change, so the state is reset and the toggle cannot be kept on.
 *
 * Stubbing this effect to a no-op leaves the React state as the single source
 * of truth: onToggleGoldBadge() still flips the value with `prev => !prev`,
 * and the toggle stays in the position the user chose for the session.
 *
 * Verified against the exact 1.247.3 bundle; other builds compile this closure
 * to different bytes and are intentionally unsupported by the compatibility.
 */
private val GOLD_BADGE_SYNC_EFFECT =
    "2904002e0104037600bc6001002901012e02010837030201aab62e05040077020e060502760290090637020502bb6453060300022e050403bc2b06052e03010e030240020678a5400205cba42e050404400205c4842e040401400204e6b153020300022e0101115c015c00".hexToByteArray()

@Suppress("unused")
val locketGoldBadgePatch = resourcePatch(
    name = PATCH_NAME,
    description = "Makes the 'Locket Gold badge' toggle work without a subscription: the " +
        "state is kept locally instead of being written to Locket's servers, which " +
        "reject badge changes from unsubscribed accounts. Turn the toggle on in " +
        "Settings > Locket Gold to show the badge. Client-side only: the choice is " +
        "not saved on Locket's servers and resets after a restart.",
    default = true
) {
    compatibleWith(LOCKET_COMPATIBILITY)

    execute {
        val bundle = get(HERMES_BUNDLE_PATH)
        val bytecode = bundle.readBytes()
        bytecode.requireHermes96(HERMES_BUNDLE_PATH)

        bytecode.stubHermesNoOp(
            GOLD_BADGE_SYNC_EFFECT,
            label = "Locket Gold badge state sync effect"
        )
        bundle.writeBytes(bytecode)
    }
}

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

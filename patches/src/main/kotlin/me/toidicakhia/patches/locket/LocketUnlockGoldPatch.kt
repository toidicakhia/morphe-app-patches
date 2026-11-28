package me.toidicakhia.patches.locket

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import me.toidicakhia.patches.shared.Constants.LOCKET_COMPATIBILITY

private const val PATCH_NAME = "Unlock Locket Gold"

/**
 * Hermes bytecode of androidGoldSubscriptionOverride() in Locket 1.247.3.
 * The compiled function reads the android_gold_subscription_override remote
 * config flag:
 *
 *   GetEnvironment r0
 *   ...
 *   LoadConstString r0, "android_gold_subscription_override"
 *   Call2 r0, getBoolean
 *   Ret r0
 *
 * Verified against the exact 1.247.3 (version code 636) XAPK. Older builds
 * compile the same function to different bytes and are intentionally unsupported.
 */
private val GOLD_OVERRIDE_METHOD =
    "2900002e000002360100015c76005102010037010202c56d730091d353000102005c00".hexToByteArray()

// LoadConstTrue r0; Ret r0 — the remaining bytes are filled with Unreachable (0x00).
private val RETURN_TRUE_STUB = byteArrayOf(0x78, 0x00, 0x5C, 0x00)

@Suppress("unused")
val locketUnlockGoldPatch = resourcePatch(
    name = PATCH_NAME,
    description = "Makes the app treat the signed-in account as a Locket Gold subscriber, " +
        "enabling the Gold-only feature gates and replacing the paywall with subscription " +
        "management. Client-side only: features validated by Locket's servers still need a " +
        "real subscription.",
    default = true
) {
    compatibleWith(LOCKET_COMPATIBILITY)
    execute {
        val bundle = get(HERMES_BUNDLE_PATH)
        val bytecode = bundle.readBytes()
        bytecode.requireHermes96(HERMES_BUNDLE_PATH)

        val offset = bytecode.indexOfSequence(GOLD_OVERRIDE_METHOD)
        if (offset == -1) {
            throw PatchException(
                "$PATCH_NAME: could not locate androidGoldSubscriptionOverride in $HERMES_BUNDLE_PATH. " +
                    "This patch supports Locket 1.247.3 only."
            )
        }
        if (bytecode.indexOfSequence(GOLD_OVERRIDE_METHOD, offset + 1) != -1) {
            throw PatchException("$PATCH_NAME: androidGoldSubscriptionOverride is not unique in $HERMES_BUNDLE_PATH.")
        }

        RETURN_TRUE_STUB.copyInto(bytecode, offset)
        bytecode.fill(0x00, offset + RETURN_TRUE_STUB.size, offset + GOLD_OVERRIDE_METHOD.size)
        bytecode.rehashHermes()
        bundle.writeBytes(bytecode)
    }
}

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

package me.toidicakhia.patches.locket

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import me.toidicakhia.patches.shared.Constants.LOCKET_COMPATIBILITY

private const val PATCH_NAME = "Unlock Locket Gold"

/**
 * Hermes bytecode of androidGoldSubscriptionOverride() in the supported builds.
 * The compiled function reads the android_gold_subscription_override remote
 * config flag:
 *
 *   GetEnvironment r0
 *   ...
 *   LoadConstString r0, "android_gold_subscription_override"
 *   Call2 r0, getBoolean
 *   Ret r0
 *
 * 1.247.1 and 1.247.3 differ only in the closure slot / string-id operands,
 * so both encodings are accepted and the matching one is stubbed in place.
 */
private val GOLD_OVERRIDE_METHODS = listOf(
    // Locket 1.247.3 (version code 636), verified against the exact XAPK.
    "2900002e000002360100015c76005102010037010202c56d730091d353000102005c00".hexToByteArray(),
    // Locket 1.247.1 (version code 632), the original target of this patch.
    "2900002e000002360100015c7600510201003701020264b67300a6d353000102005c00".hexToByteArray()
)

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

        var method: ByteArray? = null
        var offset = -1
        for (candidate in GOLD_OVERRIDE_METHODS) {
            val foundAt = bytecode.indexOfSequence(candidate)
            if (foundAt == -1) continue
            if (bytecode.indexOfSequence(candidate, foundAt + 1) != -1) {
                throw PatchException("$PATCH_NAME: androidGoldSubscriptionOverride is not unique in $HERMES_BUNDLE_PATH.")
            }
            method = candidate
            offset = foundAt
            break
        }

        val matchedMethod = method ?: throw PatchException(
            "$PATCH_NAME: could not locate androidGoldSubscriptionOverride in $HERMES_BUNDLE_PATH. " +
                "This patch supports Locket 1.247.1 and 1.247.3 only."
        )

        RETURN_TRUE_STUB.copyInto(bytecode, offset)
        bytecode.fill(0x00, offset + RETURN_TRUE_STUB.size, offset + matchedMethod.size)
        bytecode.rehashHermes()
        bundle.writeBytes(bytecode)
    }
}

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

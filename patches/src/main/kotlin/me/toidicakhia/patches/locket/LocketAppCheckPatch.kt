package me.toidicakhia.patches.locket

import app.morphe.patcher.patch.resourcePatch
import me.toidicakhia.patches.shared.Constants.LOCKET_COMPATIBILITY

private const val PATCH_NAME = "Disable App Check"

/** Small JS wrappers that activate React Native Firebase App Check. */
private val INIT_APP_CHECK =
    "76002901002e030107360203014183000801006c00005400020300015c00".hexToByteArray()
private val INIT_APP_CHECK_RECAPTCHA =
    "76002901002e030100360203014183000801006c00005400020300015c00".hexToByteArray()
private val INITIALIZE_APP_CHECK =
    "76002901002e030102360203014183000801006c00005400020300015c00".hexToByteArray()

/**
 * Skips the three App Check activation wrappers so no attestation token is
 * requested. The token getters are deliberately left alone: they must keep
 * returning strings for any code that still reads them.
 *
 * This is best effort. Firebase's native SDK can still attach App Check on its
 * own, and Locket's backend may enforce it regardless of client behaviour.
 */
@Suppress("unused")
val locketAppCheckPatch = resourcePatch(
    name = PATCH_NAME,
    description = "Stubs the React Native Firebase App Check activation wrappers so the app no " +
        "longer requests an attestation token. Best effort: server-side enforcement of " +
        "X-Firebase-AppCheck on writes and media uploads is unaffected.",
    default = false
) {
    compatibleWith(LOCKET_COMPATIBILITY)

    execute {
        val bundle = get(HERMES_BUNDLE_PATH)
        val bytecode = bundle.readBytes()
        bytecode.requireHermes96(HERMES_BUNDLE_PATH)

        bytecode.stubHermesBoolGetter(INIT_APP_CHECK, value = true, label = "initAppCheck")
        bytecode.stubHermesBoolGetter(
            INIT_APP_CHECK_RECAPTCHA, value = true, label = "initAppCheckRecaptcha"
        )
        bytecode.stubHermesBoolGetter(
            INITIALIZE_APP_CHECK, value = true, label = "initializeAppCheck wrapper"
        )
        bundle.writeBytes(bytecode)
    }
}

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

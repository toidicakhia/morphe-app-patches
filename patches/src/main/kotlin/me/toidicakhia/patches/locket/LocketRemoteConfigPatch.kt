package me.toidicakhia.patches.locket

import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.resourcePatch
import me.toidicakhia.patches.shared.Constants.LOCKET_COMPATIBILITY

private const val PATCH_NAME = "Remote Config overrides"

/**
 * 35-byte bodies of the simple RC getters in Locket 1.247.3. All are unique in
 * the Hermes bundle and stub cleanly to LoadConstTrue/False; Ret.
 */
private val RECAPTCHA_GETTERS = listOf(
    "androidSignupRecaptchaEnabled" to
        "2900002e000002360100015c76005102010037010202c56d7300c5cc53000102005c00",
    "androidUsernamesRecaptchaEnabled" to
        "2900002e000002360100015c76005102010037010202c56d7300e4cc53000102005c00",
    "androidFriendingRecaptchaEnabled" to
        "2900002e000002360100015c76005102010037010202c56d73008ed353000102005c00",
    "androidGroupMembershipRecaptchaEnabled" to
        "2900002e000002360100015c76005102010037010202c56d730046d353000102005c00",
    "androidDataExportRecaptchaEnabled" to
        "2900002e000002360100015c76005102010037010202c56d730080d353000102005c00",
    "androidLocationCaptionRecaptchaEnabled" to
        "2900002e000002360100015c76005102010037010202c56d7300a6cc53000102005c00"
)

private val HISTORY_ADS_GETTER =
    "2900002e000002360100015c76005102010037010202c56d7300d77253000102005c00"
private val SUBSCRIBE_GOLD_BUTTON_GETTER =
    "2900002e000002360100015c76005102010037010202c56d7300c7cc53000102005c00"
private val BETA_FEATURES_GETTER =
    "2900002e000002360100015c76005102010037010202c56d7300472d53000102005c00"

/**
 * Forces a handful of compiled Remote Config getters instead of relying on
 * bundled defaults: live Remote Config would otherwise override the defaults.
 * Flags not listed here are left untouched; for arbitrary server-controlled
 * values use the "Spoofer server" patch instead.
 */
@Suppress("unused")
val locketRemoteConfigPatch = resourcePatch(
    name = PATCH_NAME,
    description = "Forces selected client-side Remote Config flags regardless of what Locket's " +
        "server returns. Recaptcha and history ads can be turned off; the Gold subscribe button " +
        "and beta features can be turned on. Server-enforced features are unaffected.",
    default = false
) {
    compatibleWith(LOCKET_COMPATIBILITY)

    val disableRecaptcha by booleanOption(
        key = "disableRecaptcha",
        default = false,
        title = "Disable recaptcha gates",
        description = "Forces the six android_*_recaptcha_enabled getters to false. Backend " +
            "checks, if any, are not affected."
    )

    val disableHistoryAds by booleanOption(
        key = "disableHistoryAds",
        default = false,
        title = "Disable history ads",
        description = "Forces android_history_ads_enabled to false."
    )

    val enableSubscribeGoldButton by booleanOption(
        key = "enableSubscribeGoldButton",
        default = false,
        title = "Show Gold subscribe button",
        description = "Forces android_subscribe_gold_button_enabled to true."
    )

    val enableBetaFeatures by booleanOption(
        key = "enableBetaFeatures",
        default = false,
        title = "Enable beta features",
        description = "Forces beta_features_enabled to true."
    )

    execute {
        val bundle = get(HERMES_BUNDLE_PATH)
        val bytecode = bundle.readBytes()
        bytecode.requireHermes96(HERMES_BUNDLE_PATH)

        if (disableRecaptcha == true) {
            for ((name, hex) in RECAPTCHA_GETTERS) {
                bytecode.stubHermesBoolGetter(hex.hexToByteArray(), value = false, label = name)
            }
        }
        if (disableHistoryAds == true) {
            bytecode.stubHermesBoolGetter(
                HISTORY_ADS_GETTER.hexToByteArray(), value = false, label = "androidHistoryAdsEnabled"
            )
        }
        if (enableSubscribeGoldButton == true) {
            bytecode.stubHermesBoolGetter(
                SUBSCRIBE_GOLD_BUTTON_GETTER.hexToByteArray(),
                value = true,
                label = "androidSubscribeGoldButtonEnabled"
            )
        }
        if (enableBetaFeatures == true) {
            bytecode.stubHermesBoolGetter(
                BETA_FEATURES_GETTER.hexToByteArray(), value = true, label = "betaFeaturesEnabled"
            )
        }

        bundle.writeBytes(bytecode)
    }
}

private fun String.hexToByteArray(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

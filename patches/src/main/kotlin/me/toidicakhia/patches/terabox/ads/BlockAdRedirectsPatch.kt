/*
 * Copyright (C) 2026 hxreborn
 * SPDX-License-Identifier: GPL-3.0-only
 */
package me.toidicakhia.patches.terabox.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import me.toidicakhia.patches.shared.Constants
import me.toidicakhia.patches.shared.misc.smali.parameterRegister

/**
 * Every store/market URL the ad SDKs fall back to is rewritten to a blank page.
 *
 * The click-through ladder in these SDKs is always "market://details?id=<app>"
 * first and "https://play.google.com/store/apps/details?id=<app>" second, each
 * wrapped in its own try/catch because the store may not be installed. Pointing
 * both at `about:blank` makes those launches fail inside the SDK, where the
 * exception is already handled, instead of leaving TeraBox and stalling the
 * automation in another app.
 *
 * The rewrite is limited to the ad SDK packages and the app's own WebView
 * client, so the app's "rate us", update and help links keep working.
 */
private const val BLOCKED_URL = "about:blank"

private const val WEBVIEW_HANDOFF = "Lme/toidicakhia/extension/terabox/TeraboxWebViewHandoff;"

/** Packages that launch the store or a browser from an ad. */
private val AD_PACKAGE_PREFIXES = listOf(
    // Pangle / ByteDance.
    "Lcom/bytedance/sdk/openadsdk/",
    // MAX / AppLovin.
    "Lcom/applovin/",
    // MBridge / Mintegral.
    "Lcom/mbridge/",
    // InMobi.
    "Lcom/inmobi/",
    // Bigo.
    "Lsg/bigo/",
    // AdMaster.
    "Lcc/admaster/",
    // Vlion.
    "Lcom/advlion/",
    // PubNative / Verve.
    "Lnet/pubnative/",
    // AdMob and its internals.
    "Lcom/google/android/gms/ads/",
    "Lcom/google/android/gms/internal/ads/",
    // Moloco.
    "Lcom/moloco/",
    // Smaato.
    "Lcom/smaato/",
    // Vungle.
    "Lcom/vungle/",
    // Meta.
    "Lcom/facebook/ads/",
    // Mars ADX, the app's ad mediation.
    "Lcom/mars/united/international/ads/",
    // The app's WebView client, which the hybrid pages inherit from.
    "Lcom/dubox/drive/business/widget/webview/client/",
)

/**
 * True for the hand-off URLs an ad can carry: the Play Store, a `market:`
 * deep link, a Chrome-style `intent:` URL or its browser fallback.
 */
private fun isStoreHandoff(value: String): Boolean =
    value.contains("market://") ||
        value.contains("market:") ||
        value.contains("play.google.com") ||
        value.startsWith("intent://") ||
        value.contains("browser_fallback_url")

/**
 * `n(String)Z` is the app's "open the store" helper: it builds
 * `market:<pkg>` and falls back to `https://play.google.com/store/apps/...`,
 * then starts the resolved activity. Returning `true` says "handled" without
 * launching anything, so the WebView also cancels the navigation.
 */
internal object WebViewStoreHandoffFingerprint : Fingerprint(
    definingClass = "Lcom/dubox/drive/business/widget/webview/client/____;",
    name = "n",
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
)

/**
 * `r(WebView, String)Z` is the app's generic hand-off to another app or the
 * browser for a URL the WebView will not load itself. It also carries the
 * app's own `terabox://` and `dubox://` deep links, so only the hand-offs that
 * leave TeraBox may be refused.
 */
internal object WebViewExternalHandoffFingerprint : Fingerprint(
    definingClass = "Lcom/dubox/drive/business/widget/webview/client/____;",
    name = "r",
    returnType = "Z",
    parameters = listOf(
        "Landroid/webkit/WebView;",
        "Ljava/lang/String;",
    ),
)

/**
 * Ad clicks are the one place where TeraBox lets an external app take over the
 * screen: the store opens, the automation loses the foreground and the clicker
 * pauses until the user comes back. This patch keeps every one of those
 * hand-offs inside the app, without touching the app's own deep links.
 */
@Suppress("unused")
val blockAdRedirectsPatch = bytecodePatch(
    name = "Block ad redirects",
    description = "Rewrites the Play Store, market: and intent: URLs inside the ad SDKs and stops " +
        "the hybrid WebView client from handing a page off to the store or a browser, so an " +
        "auto-opening or mis-tapped ad cannot leave TeraBox and stall the automation.",
    default = true
) {
    compatibleWith(Constants.TERABOX_COMPATIBILITY)

    execute {
        // The store helper only ever builds market:/play.google.com URLs, so
        // refusing it outright is safe.
        WebViewStoreHandoffFingerprint.matchSingle().method.returnEarly(true)

        // The generic hand-off also carries the app's own deep links. Returning
        // true for those swallowed them, so a tap on a video card (or any other
        // terabox:// control) on a hybrid page did nothing. Let the original
        // method handle the app's schemes and keep refusing the rest.
        val handoff = WebViewExternalHandoffFingerprint.matchSingle().method
        handoff.addInstructions(
            0,
            """
                invoke-static {${handoff.parameterRegister(1)}}, $WEBVIEW_HANDOFF->isInAppPage(Ljava/lang/String;)Z
                move-result v0
                if-eqz v0, :tb_in_app_page
                const/4 v0, 0x1
                return v0
                :tb_in_app_page
            """.trimIndent()
        )

        val types = mutableListOf<String>()
        classDefForEach { classDef ->
            if (AD_PACKAGE_PREFIXES.any { classDef.type.startsWith(it) }) {
                types += classDef.type
            }
        }

        var rewritten = 0
        for (type in types) {
            val classDef = mutableClassDefBy(type)
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue
                for (index in implementation.instructions.indices) {
                    val instruction = method.getInstruction(index)
                    if (instruction.opcode != Opcode.CONST_STRING &&
                        instruction.opcode != Opcode.CONST_STRING_JUMBO
                    ) {
                        continue
                    }

                    val reference = (instruction as ReferenceInstruction).reference as? StringReference
                    val value = reference?.string ?: continue
                    if (!isStoreHandoff(value)) {
                        continue
                    }

                    val register = (instruction as OneRegisterInstruction).registerA
                    if (register > 0xFF) {
                        continue
                    }
                    method.replaceInstruction(index, "const-string v$register, \"$BLOCKED_URL\"")
                    rewritten++
                }
            }
        }

        if (rewritten == 0) {
            throw PatchException("Block ad redirects: no Play Store or market URL found")
        }
    }
}

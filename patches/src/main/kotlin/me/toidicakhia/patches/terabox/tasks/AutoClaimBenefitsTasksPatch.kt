/*
 * Copyright (C) 2026 hxreborn
 * SPDX-License-Identifier: GPL-3.0-only
 */
package me.toidicakhia.patches.terabox.tasks

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.matchSingle
import me.toidicakhia.patches.shared.Constants
import me.toidicakhia.patches.terabox.ads.autoRewardAdsPatch

private const val TASK_CLICKER = "Lme/toidicakhia/extension/terabox/TeraboxTaskClicker;"

/**
 * The task center is a remote FE page, so the click target cannot be hardcoded.
 * The injected script matches controls by their label (watch / claim / earn in
 * several languages), skips destructive ones and presses them one by one.
 */
@Suppress("unused")
val autoClaimBenefitsTasksPatch = bytecodePatch(
    name = "Auto claim Benefits tasks",
    description = "Injects a clicker into the Benefits/task-center webview: it keeps pressing " +
        "\"watch ad\", \"claim\" and other reward controls, with a cooldown and without touching " +
        "purchase, share or uninstall buttons. Use it together with \"Auto reward ads\" so the ad " +
        "closes itself once watched; the remote page layout can still change at any time.",
    default = false
) {
    compatibleWith(Constants.TERABOX_COMPATIBILITY)
    dependsOn(autoRewardAdsPatch)
    extendWith("extensions/extension.mpe")

    execute {
        // The hybrid bridge is created before the page loads. Hand the WebView
        // to the extension, which injects the clicker once the task center is
        // actually open.
        HybridBridgeFingerprint.matchSingle().method.addInstructions(
            0,
            "invoke-static {p2}, $TASK_CLICKER->attach(Landroid/webkit/WebView;)V"
        )
    }
}

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
 * The task center is an offline FE pack the app downloads, so its class names
 * are stable per pack version but change between packs. The script targets
 * those selectors only, never labels, and skips destructive controls.
 */
@Suppress("unused")
val autoClaimBenefitsTasksPatch = bytecodePatch(
    name = "Auto claim Benefits tasks",
    description = "Presses the Benefits/Gold-center controls and shows what it clicked: the daily " +
        "video chain, claimable rewards, the watch-video task and the check-in pill. Share, save, " +
        "game and purchase tasks are skipped, as are disabled controls. A control that does " +
        "nothing is retried later, a task that never completes is left after three tries, and the " +
        "check-in page closes itself when done. Pair it with \"Auto reward ads\".",
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

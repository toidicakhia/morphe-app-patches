/*
 * Copyright (C) 2026 hxreborn
 * SPDX-License-Identifier: GPL-3.0-only
 */
package me.toidicakhia.patches.terabox.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.matchSingle
import app.morphe.patcher.util.returnEarly
import me.toidicakhia.patches.shared.Constants

private const val REWARD_SHOW_LISTENER =
    "Lcom/mars/united/international/ads/adsource/reward/OnRewardShowListener;"

/**
 * The reward callback (`OnRewardShowListener.___`) is what actually unlocks the
 * reward: it writes the privilege locally and answers the Benefits H5 page with
 * `isSuccess`, which then claims the task. Skipping the mediation show call and
 * firing the callback directly makes every rewarded placement finish instantly.
 */
@Suppress("unused")
val autoRewardAdsPatch = bytecodePatch(
    name = "Auto reward ads",
    description = "Skips rewarded-video playback. Every rewarded ad request (Benefits task center, " +
        "download speed-up, video quality, transfer files) reports success and runs the reward " +
        "callback immediately. Rewards that TeraBox validates on its servers can still be refused. " +
        "Pair it with \"Spoof signature\" when patching with this bundle only.",
    default = true
) {
    compatibleWith(Constants.TERABOX_COMPATIBILITY)

    execute {
        // Report the placement as loaded and showable so callers take the
        // "show now" path instead of loading a real ad first.
        RewardAdReadyFingerprint.matchSingle().method.returnEarly(true)
        RewardAdShowableFingerprint.matchSingle().method.returnEarly(true)

        // Hand control back to the caller with success, after firing the reward
        // callback the app would normally run when the video completes.
        RewardAdShowFingerprint.matchSingle().method.addInstructions(
            0,
            """
                if-eqz p2, :reward_done
                invoke-interface {p2}, $REWARD_SHOW_LISTENER->___()V
                :reward_done
                const/4 v0, 0x1
                return v0
            """.trimIndent()
        )
    }
}

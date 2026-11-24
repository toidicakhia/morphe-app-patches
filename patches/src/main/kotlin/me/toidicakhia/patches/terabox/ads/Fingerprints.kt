/*
 * Copyright (C) 2026 hxreborn
 * SPDX-License-Identifier: GPL-3.0-only
 */
package me.toidicakhia.patches.terabox.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

private const val REWARD_SHOW_LISTENER =
    "Lcom/mars/united/international/ads/adsource/reward/OnRewardShowListener;"

/**
 * Proxy listener created by the mediation's `showAdIfAvailable`. Every reward
 * callback the ad SDK delivers is forwarded through `___()` to the caller.
 *
 * Moved from `vk0/l$__` in 4.28.5; the shape is unchanged: the listener is
 * the `_` field, the mediator is `__`, and `___()` is the reward-granted one.
 */
internal object RewardEarnedFingerprint : Fingerprint(
    definingClass = "Ldl0/l\$__;",
    name = "___",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
    filters = listOf(
        methodCall(definingClass = REWARD_SHOW_LISTENER, name = "___"),
    ),
)

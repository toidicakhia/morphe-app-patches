/*
 * Copyright (C) 2026 hxreborn
 * SPDX-License-Identifier: GPL-3.0-only
 */
package me.toidicakhia.patches.terabox.ads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import com.android.tools.smali.dexlib2.AccessFlags

// Obfuscated app-side wrapper around the Mars mediation SDK for rewarded ads.
private const val REWARD_AD_WRAPPER = "Ldk0/_;"
private const val REWARD_SHOW_LISTENER =
    "Lcom/mars/united/international/ads/adsource/reward/OnRewardShowListener;"

/**
 * `showAdIfAvailable`: called with the caller's reward listener and returns
 * whether the ad was handed to the mediation SDK.
 */
internal object RewardAdShowFingerprint : Fingerprint(
    definingClass = REWARD_AD_WRAPPER,
    name = "b",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(
        "Landroidx/fragment/app/FragmentActivity;",
        REWARD_SHOW_LISTENER,
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
    filters = listOf(
        methodCall(definingClass = "Luk0/l;", name = "n"),
    ),
)

/** `isAdLoaded`: ad loaded and the placement's remote switch allows showing it. */
internal object RewardAdReadyFingerprint : Fingerprint(
    definingClass = REWARD_AD_WRAPPER,
    name = "_",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(
        methodCall(definingClass = "Lkotlin/jvm/functions/Function0;", name = "invoke"),
    ),
)

/** `isAdReady`: loaded and at least one mediation source reports it can show. */
internal object RewardAdShowableFingerprint : Fingerprint(
    definingClass = REWARD_AD_WRAPPER,
    name = "___",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = emptyList(),
    filters = listOf(
        methodCall(definingClass = REWARD_AD_WRAPPER, name = "_"),
        methodCall(definingClass = "Luk0/l;", name = "h"),
    ),
)

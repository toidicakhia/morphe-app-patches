package app.apppatches.patches.vtvgo

import app.apppatches.patches.shared.Constants.VTVGO_COMPATIBILITY
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.matchSingle

@Suppress("unused")
val vtvgoUnlockVideoQualityPatch = bytecodePatch(
    name = "Unlock video quality",
    description = "Removes the player's video quality constraints so free-tier, battery-saver and " +
        "mobile-data caps no longer block the best available resolution.",
    default = true
) {
    compatibleWith(VTVGO_COMPATIBILITY)

    execute {
        // setTrackParameters(width, height, bitrate) applies the quality cap.
        // Clearing all three values makes the method remove the size limits and
        // raise the bitrate ceiling, letting the player pick the best rendition.
        SetTrackParametersFingerprint.matchSingle().method.addInstructions(
            0,
            """
                const/4 p1, 0x0
                const/4 p2, 0x0
                const/4 p3, 0x0
            """
        )
    }
}

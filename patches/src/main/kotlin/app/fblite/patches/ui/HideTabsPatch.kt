package app.fblite.patches.ui

import app.fblite.patches.ads.hideSponsoredPostsPatch
import app.fblite.patches.font.AttachBaseContextFingerprint
import app.fblite.patches.shared.Constants.COMPATIBILITY_FACEBOOK_LITE
import app.fblite.patches.video.featureMarks
import app.fblite.patches.video.videoFeaturesBasePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Hides the Friends, Reels and Marketplace tabs of the top bar. The tabs are components of the server-driven tree and are
 * flagged hidden as they are decoded, which is what the hide-ads patch's decoder replacement already sees, so this depends on
 * it. Which tabs go is chosen in the Morphe settings screen (applies after a restart).
 */
@Suppress("unused")
val hideTabsPatch = bytecodePatch(
    name = "Hide top bar tabs",
    description = "Adds switches to hide the Friends, Reels and Marketplace tabs of the top bar. Needs \"Hide sponsored posts\" " +
        "(its decoder is used). Switched in the Morphe settings screen; applies after the app restarts.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FACEBOOK_LITE)

    dependsOn(hideSponsoredPostsPatch, videoFeaturesBasePatch)

    extendWith("extensions/extension.mpe")

    execute {
        AttachBaseContextFingerprint.method.addInstructions(
            0,
            featureMarks("hide_tab_friends", "hide_tab_reels", "hide_tab_marketplace")
        )
    }
}

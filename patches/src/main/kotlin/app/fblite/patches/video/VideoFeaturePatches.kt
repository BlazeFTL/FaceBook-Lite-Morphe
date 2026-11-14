package app.fblite.patches.video

import app.fblite.patches.font.AttachBaseContextFingerprint
import app.fblite.patches.shared.Constants.COMPATIBILITY_FACEBOOK_LITE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * One selectable patch per video feature. Each marks its feature as patched in; the extension keeps a feature off,
 * and out of the Morphe settings screen, when its patch is not selected. Once patched in, a feature can still be
 * switched off in the settings screen.
 */
private fun videoFeaturePatch(name: String, description: String, vararg keys: String) = bytecodePatch(
    name = name,
    description = description,
    default = true
) {
    compatibleWith(COMPATIBILITY_FACEBOOK_LITE)

    dependsOn(videoFeaturesBasePatch)

    extendWith("extensions/extension.mpe")

    execute {
        AttachBaseContextFingerprint.method.addInstructions(0, featureMarks(*keys))
    }
}

@Suppress("unused")
val shortVideoControlsPatch = videoFeaturePatch(
    name = "Seek bar for short videos",
    description = "Adds a seek bar and the back and forward 10 second buttons to reels and videos " +
        "under a minute, when the app's own controls are not showing. Switched in the Morphe settings screen.",
    "short_video_controls"
)

@Suppress("unused")
val doubleTapSeekPatch = videoFeaturePatch(
    name = "Double tap to seek",
    description = "Double tap the left or right side of a reel or video to go back or forward 10 seconds. " +
        "Switched in the Morphe settings screen.",
    "double_tap_seek"
)

@Suppress("unused")
val skipEmptyReelsPagePatch = videoFeaturePatch(
    name = "Open profile when no reels",
    description = "When a creator's Reels page says \"No reels yet\", opens their main profile for you (3 dot menu > " +
        "View main profile). Switched in the Morphe settings screen.",
    "profile_when_no_reels"
)

@Suppress("unused")
val skipCreatorReelsPagePatch = videoFeaturePatch(
    name = "Skip creator's Reels page",
    description = "When a creator's Reels page opens (tapping their name in a reel), always opens their main profile " +
        "and its Reels tab, even when the page has reels. Switched in the Morphe settings screen.",
    "profile_always"
)

@Suppress("unused")
val logoSettingsPatch = videoFeaturePatch(
    name = "Long press logo for settings",
    description = "Long press the \"facebook\" logo at the top left of the Home screen to open the Morphe settings. " +
        "With Debug logging on, a long press there also writes what is on screen to the log. Switched in the Morphe settings screen.",
    "logo_settings"
)

@Suppress("unused")
val hideMenuOptionsPatch = videoFeaturePatch(
    name = "Hide reel menu options",
    description = "Hides the rows you choose in a reel's 3 dot menu (Save reel, Copy link, Playback speed ...). " +
        "Chosen in the Morphe settings screen. The row names are English.",
    "hide_menu_items"
)

@Suppress("unused")
val saveAllPhotosPatch = videoFeaturePatch(
    name = "Save all photos",
    description = "While a photo's menu is open (Save, I don't want to see this ...), shows a button that saves every photo of " +
        "the post to Pictures/Facebook Lite. Switched in the Morphe settings screen.",
    "save_all_photos"
)

@Suppress("unused")
val hideTabsPatch = videoFeaturePatch(
    name = "Hide top bar tabs",
    description = "Adds switches to hide the Friends, Reels and Marketplace tabs of the top bar; the tabs that stay share the row. " +
        "Switched in the Morphe settings screen.",
    "hide_tab_friends", "hide_tab_reels", "hide_tab_marketplace"
)

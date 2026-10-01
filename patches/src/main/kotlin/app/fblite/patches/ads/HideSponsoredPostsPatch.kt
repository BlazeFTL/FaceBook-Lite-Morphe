package app.fblite.patches.ads

import app.fblite.patches.font.AttachBaseContextFingerprint
import app.fblite.patches.shared.Constants.COMPATIBILITY_FACEBOOK_LITE_530
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch

private const val EXTENSION_CLASS = "Lapp/fblite/extension/hideads/HideSponsoredPostsPatch;"

/**
 * Sponsored posts are inserted by the server into the feed's component tree, which is decoded by
 * X.1DY in the secondary dex. The extension adds a class with the same name that delegates to the
 * original and hides sponsored posts afterwards. This loads it at startup, before the secondary dex
 * exists, so it takes precedence.
 */
@Suppress("unused")
val hideSponsoredPostsPatch = bytecodePatch(
    name = "Hide sponsored posts",
    description = "Hides sponsored posts in the news feed.",
    default = true
) {
    compatibleWith(COMPATIBILITY_FACEBOOK_LITE_530)

    extendWith("extensions/hideads.mpe")

    execute {
        AttachBaseContextFingerprint.method.addInstruction(
            0,
            // attachBaseContext has 31 locals, so p1 is above v15 and needs the range form.
            "invoke-static/range { p1 .. p1 }, $EXTENSION_CLASS->loadDecoder(Landroid/content/Context;)V"
        )
    }
}

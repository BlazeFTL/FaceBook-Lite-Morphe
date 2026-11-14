package app.fblite.extension.hideads;

import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * Hides tabs of the top bar (Friends, Reels, Marketplace): each is flagged hidden (A2e) with width 0 (A2A) as the app decodes it.
 *
 * From the log of 536.0.0.11.107: the top bar is the component #30000 under the root #30005 (outside the feed container
 * #30001). Its second child is the row of tabs (#0, six children of 180 px), and each tab has a fixed id: Home #1, Friends #5,
 * Messenger #6, Reels #7, Notifications #8, Marketplace #9. The tab is recognised by its id and by that parent chain
 * (tab < row #0 < #30000), so no other component with the same id is touched. A node is seen right after it is decoded,
 * before its children, so each tab is handled alone, never through its row.
 *
 * Settings are read by name from the Morphe preferences and apply from the next decode (a restart). Debug log, tag "tabs":
 * every tab seen and whether it was hidden.
 */
public final class UiTweaks {
    private static final int TOP_BAR_ID = 30000;
    private static final long SETTING_CHECK_MS = 1000;
    private static final String TAG = "tabs";

    private static boolean resolved;
    private static boolean disabled;
    private static Class<?> componentClass;
    private static Field fId, fParent, fWidth, fHidden;
    private static long checkedAt = -SETTING_CHECK_MS;
    private static boolean hideFriends, hideReels, hideMarketplace;

    private UiTweaks() {
    }

    /** Called after the app decoded a container (from {@link SponsoredPosts#afterDecode}). */
    static void afterDecode(Object component) {
        if (component == null || disabled) return;
        try {
            long now = SystemClock.uptimeMillis();
            if (now - checkedAt >= SETTING_CHECK_MS) {
                checkedAt = now;
                hideFriends = AdLog.setting("hide_tab_friends", false);
                hideReels = AdLog.setting("hide_tab_reels", false);
                hideMarketplace = AdLog.setting("hide_tab_marketplace", false);
            }
            if (!hideFriends && !hideReels && !hideMarketplace) return;
            if (!resolved) resolve();
            if (!componentClass.isInstance(component)) return;
            short id = fId.getShort(component);
            if (id != 5 && id != 7 && id != 9) return;
            Object row = fParent.get(component);
            if (row == null || fId.getShort(row) != 0) return;
            Object bar = fParent.get(row);
            if (bar == null || fId.getShort(bar) != TOP_BAR_ID) return;
            boolean hide = id == 5 ? hideFriends : id == 7 ? hideReels : hideMarketplace;
            String name = id == 5 ? "Friends" : id == 7 ? "Reels" : "Marketplace";
            if (!hide) return;
            fHidden.setBoolean(component, true);
            fWidth.setInt(component, 0);
            if (AdLog.enabled()) AdLog.log(TAG, "TAB #" + id + " (" + name + ") hidden");
        } catch (Throwable t) {
            disabled = true;
            Log.e("FBLiteMorphe", "Hiding tabs failed, turning it off", t);
        }
    }

    private static void resolve() throws Exception {
        ClassLoader app = UiTweaks.class.getClassLoader();
        componentClass = Class.forName("X.0hd", false, app);
        fId = componentClass.getDeclaredField("A1c");
        fParent = componentClass.getDeclaredField("A19");
        fWidth = componentClass.getDeclaredField("A2A");
        fHidden = componentClass.getDeclaredField("A2e");
        fId.setAccessible(true);
        fParent.setAccessible(true);
        fWidth.setAccessible(true);
        fHidden.setAccessible(true);
        resolved = true;
    }
}

package app.fblite.extension.video;

import android.view.View;

import java.util.HashSet;
import java.util.Set;

import app.fblite.extension.settings.MorpheLog;

/**
 * Debug only (Morphe settings, Debug logging): when a video starts playing, logs where Facebook keeps its
 * sources, to find out why a download is only the SD file. Each video is logged once.
 */
final class VideoProbe {
    private static final int FULL_DUMPS = 5;
    private static final int MAX_SEEN = 500;
    private static final Set<String> SEEN = new HashSet<>();
    private static int fullDumps;

    private VideoProbe() {
    }

    static void onPlaying(View video) {
        if (!MorpheLog.enabled()) return;
        try {
            Object[] roots = DownloadButton.roots(video);
            Object props = roots[0];
            String id = null;
            if (props != null) {
                try {
                    id = (String) ObjectScan.field(props, "A0b");
                } catch (Throwable ignored) {
                }
            }
            String key = id != null ? id : "view" + System.identityHashCode(video);
            if (SEEN.size() > MAX_SEEN) SEEN.clear();
            if (!SEEN.add(key)) return;
            report("PLAYING", video, roots, id, fullDumps++ < FULL_DUMPS);
        } catch (Throwable t) {
            MorpheLog.log("probe", "Probe failed", t);
        }
    }

    /** Same report for the video a download was just asked for, always with the full dump. */
    static void onDownload(View video, Object[] roots, String id) {
        if (!MorpheLog.enabled()) return;
        try {
            report("DOWNLOAD", video, roots, id, true);
        } catch (Throwable t) {
            MorpheLog.log("probe", "Probe failed", t);
        }
    }

    private static void report(String what, View video, Object[] roots, String id, boolean full) {
        ObjectScan scan = ObjectScan.run(roots);
        StringBuilder sb = new StringBuilder(what).append(" id=").append(id)
                .append(" view=").append(video.getClass().getName())
                .append(' ').append(video.getWidth()).append('x').append(video.getHeight())
                .append(" nodes=").append(scan.nodes)
                .append(" manifests=").append(scan.manifests.size())
                .append(" urls=").append(scan.urls.size());
        MorpheLog.log("probe", sb.toString());
        for (ObjectScan.Hit hit : scan.urls) MorpheLog.log("probe", "  URL " + hit.path + " " + MorpheLog.url(hit.text));
        for (ObjectScan.Hit hit : scan.manifests) {
            DashManifest manifest = DashManifest.parse(hit.text);
            MorpheLog.log("probe", "  MANIFEST " + hit.path + " len=" + hit.text.length() + " duration=" + manifest.durationSeconds
                    + "s tracks=" + manifest.tracks.size());
            for (DashManifest.Track track : manifest.tracks) MorpheLog.log("probe", "    " + track);
            MorpheLog.log("probe", "  manifest head: " + hit.text.substring(0, Math.min(hit.text.length(), 300)).replace('\n', ' '));
        }
        if (scan.manifests.isEmpty()) MorpheLog.log("probe", "  no DASH manifest reachable from props, command, player or view");
        if (full) {
            for (int i = 0; i < roots.length && i < ObjectScan.ROOT_NAMES.length; i++) {
                if (i == 3) continue;
                ObjectScan.dump("probe", ObjectScan.ROOT_NAMES[i], roots[i], 3, 220);
            }
        }
    }
}

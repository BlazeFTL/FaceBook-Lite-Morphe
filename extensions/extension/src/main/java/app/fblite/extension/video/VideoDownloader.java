package app.fblite.extension.video;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import app.fblite.extension.settings.MorpheSettings;

/**
 * Saves a video. The progressive MP4 Facebook Lite hands the player is the SD file. Every video also carries
 * a DASH manifest with all qualities, each as a separate picture file and sound file, so for HD the two
 * tracks of the chosen quality are fetched and joined into one MP4 with MediaMuxer (no re-encoding).
 *
 * The manifest is found by its content (it is the text holding "&lt;MPD"), not by a field name, so this does
 * not depend on obfuscated names. Any failure of the HD path falls back to the SD file.
 *
 * Joining approach adapted from Hushfacebook's DashSave (GPL-3.0).
 */
final class VideoDownloader {
    /** What the button found for one video. */
    static final class Source {
        final String sdUrl;
        final String id;
        final Object[] roots;

        Source(String sdUrl, String id, Object... roots) {
            this.sdUrl = sdUrl;
            this.id = id;
            this.roots = roots;
        }
    }

    private static final class Option {
        final String label;
        final String suffix;
        final DashManifest.Track video;
        final DashManifest.Track audio;

        Option(String label, String suffix, DashManifest.Track video, DashManifest.Track audio) {
            this.label = label;
            this.suffix = suffix;
            this.video = video;
            this.audio = audio;
        }

        boolean isSd() {
            return video == null;
        }
    }

    private static final int MAX_NODES = 512;
    private static final int MAX_DEPTH = 3;
    private static final long MAX_TRACK_BYTES = 600L * 1024 * 1024;
    private static final int MAX_SAMPLE = 16 * 1024 * 1024;
    private static final String FOLDER = "Facebook Lite";
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private VideoDownloader() {
    }

    static void start(Activity activity, Source source) {
        List<Option> options = new ArrayList<>();
        try {
            String xml = findManifest(source.roots);
            if (xml == null) {
                VideoFeatures.log("No DASH manifest found, " + (source.sdUrl == null ? "no SD file either" : "using the SD file"), null);
                dumpFields(source.roots);
            } else {
                DashManifest manifest = DashManifest.parse(xml);
                DashManifest.Track audio = manifest.audio();
                // Never save a silent file when the video has sound that cannot be joined.
                boolean joinable = audio != null || !manifest.hasAnyAudio();
                List<DashManifest.Track> videos = manifest.videoChoices();
                VideoFeatures.log("Manifest tracks " + manifest.tracks + ", joinable " + joinable, null);
                if (joinable) {
                    for (DashManifest.Track video : videos) {
                        long bytes = (long) ((video.bandwidth + (audio == null ? 0 : audio.bandwidth)) / 8.0 * manifest.durationSeconds);
                        String label = video.quality() + "p" + (bytes > 0 ? " · ~" + size(bytes) : "");
                        options.add(new Option(label, video.quality() + "p", video, audio));
                    }
                }
            }
        } catch (Throwable t) {
            VideoFeatures.log("Reading the manifest failed", t);
            options.clear();
        }
        if (source.sdUrl != null) options.add(new Option(vi() ? "SD · tệp gốc, nhanh" : "SD · original file, fast", "SD", null, null));
        if (options.isEmpty()) {
            toast(activity, vi() ? "Không lấy được link video này" : "This video has no downloadable link");
            return;
        }
        if (options.size() == 1 || !MorpheSettings.isEnabled(activity, MorpheSettings.ASK_DOWNLOAD_QUALITY)) {
            run(activity, source, options.get(0));
            return;
        }
        String[] labels = new String[options.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = options.get(i).label;
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(vi() ? "Chất lượng tải xuống" : "Download quality")
                    .setItems(labels, (dialog, which) -> run(activity, source, options.get(which)))
                    .show();
        } catch (Throwable t) {
            VideoFeatures.log("Quality dialog failed, saving the best quality", t);
            run(activity, source, options.get(0));
        }
    }

    private static void run(Activity activity, Source source, Option option) {
        String name = (source.id != null ? source.id : String.valueOf(System.currentTimeMillis()));
        if (option.isSd()) {
            enqueueSd(activity, source.sdUrl, name + ".mp4");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            toast(activity, vi() ? "Đang tải một video khác" : "Another download is in progress");
            return;
        }
        final Context app = activity.getApplicationContext();
        final String sdUrl = source.sdUrl;
        final String fileName = name + "_" + option.suffix + ".mp4";
        toast(app, (vi() ? "Đang tải " : "Downloading ") + option.suffix + "…");
        new Thread(() -> {
            File dir = new File(app.getCacheDir(), "fblite-video");
            File videoFile = new File(dir, "video.mp4");
            File audioFile = new File(dir, "audio.mp4");
            File joined = new File(dir, "joined.mp4");
            try {
                clear(dir);
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No work folder");
                fetch(option.video.url, videoFile);
                if (option.audio != null) fetch(option.audio.url, audioFile);
                join(videoFile, option.audio == null ? null : audioFile, joined);
                save(app, joined, fileName);
                VideoFeatures.log("Saved " + fileName, null);
                toast(app, vi() ? "Đã lưu vào Movies/" + FOLDER : "Saved to Movies/" + FOLDER);
            } catch (Throwable t) {
                VideoFeatures.log("HD download failed", t);
                if (sdUrl != null) {
                    toast(app, vi() ? "Tải HD thất bại, đang tải bản SD" : "HD failed, saving the SD file");
                    VideoFeatures.MAIN.post(() -> enqueueSd(app, sdUrl, name + ".mp4"));
                } else {
                    toast(app, vi() ? "Tải video thất bại" : "Download failed");
                }
            } finally {
                clear(dir);
                BUSY.set(false);
            }
        }, "FbLiteVideoDownload").start();
    }

    private static void enqueueSd(Context context, String url, String name) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                    .setTitle(name)
                    .setMimeType("video/mp4")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, FOLDER + "/" + name);
            ((DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(request);
            VideoFeatures.log("Downloading " + name + " from " + url, null);
            toast(context, vi() ? "Đang tải video vào Movies/" + FOLDER : "Downloading to Movies/" + FOLDER);
        } catch (Throwable t) {
            VideoFeatures.log("SD download failed", t);
            toast(context, vi() ? "Tải video thất bại" : "Download failed");
        }
    }

    // ------------------------------------------------------------------------------------ network and files

    private static void fetch(String address, File to) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_TRACK_BYTES) throw new IOException("Track too large");
                    out.write(buffer, 0, n);
                }
            }
            if (total == 0) throw new IOException("Empty track");
        } finally {
            connection.disconnect();
        }
    }

    private static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    /** Copies the samples of both files into one MP4, interleaved by time. Nothing is decoded or encoded. */
    private static void join(File videoFile, File audioFile, File out) throws IOException {
        MediaExtractor video = new MediaExtractor();
        MediaExtractor audio = null;
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            video.setDataSource(videoFile.getAbsolutePath());
            int videoTrack = firstTrack(video, "video/");
            if (videoTrack < 0) throw new IOException("No video track");
            video.selectTrack(videoTrack);
            MediaFormat videoFormat = video.getTrackFormat(videoTrack);

            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoOut = muxer.addTrack(videoFormat);
            int audioOut = -1;
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                int rotation = videoFormat.getInteger(MediaFormat.KEY_ROTATION);
                if (rotation == 90 || rotation == 180 || rotation == 270) muxer.setOrientationHint(rotation);
            }
            if (audioFile != null) {
                audio = new MediaExtractor();
                audio.setDataSource(audioFile.getAbsolutePath());
                int audioTrack = firstTrack(audio, "audio/");
                if (audioTrack < 0) throw new IOException("No audio track");
                audio.selectTrack(audioTrack);
                audioOut = muxer.addTrack(audio.getTrackFormat(audioTrack));
            }
            muxer.start();
            started = true;

            ByteBuffer buffer = ByteBuffer.allocate(2 * 1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean videoDone = false;
            boolean audioDone = audio == null;
            while (true) {
                long videoTime = Long.MAX_VALUE;
                if (!videoDone) {
                    videoTime = video.getSampleTime();
                    if (videoTime < 0) {
                        videoDone = true;
                        videoTime = Long.MAX_VALUE;
                    }
                }
                long audioTime = Long.MAX_VALUE;
                if (!audioDone) {
                    audioTime = audio.getSampleTime();
                    if (audioTime < 0) {
                        audioDone = true;
                        audioTime = Long.MAX_VALUE;
                    }
                }
                if (videoDone && audioDone) break;

                boolean useVideo = videoTime <= audioTime;
                MediaExtractor source = useVideo ? video : audio;
                long size = source.getSampleSize();
                if (size > buffer.capacity()) {
                    if (size > MAX_SAMPLE) throw new IOException("Sample too large");
                    buffer = ByteBuffer.allocate((int) size);
                }
                buffer.clear();
                int read = source.readSampleData(buffer, 0);
                if (read < 0) {
                    if (useVideo) videoDone = true;
                    else audioDone = true;
                    continue;
                }
                int flags = (source.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                info.set(0, read, source.getSampleTime(), flags);
                muxer.writeSampleData(useVideo ? videoOut : audioOut, buffer, info);
                source.advance();
            }
            muxer.stop();
            started = false;
        } finally {
            video.release();
            if (audio != null) audio.release();
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Throwable ignored) {
                }
                muxer.release();
            }
        }
    }

    private static int firstTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) return i;
        }
        return -1;
    }

    /** Puts the finished file into Movies/Facebook Lite. */
    private static void save(Context context, File file, String name) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore refused the file");
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) throw new IOException("No output stream");
                copy(file, out);
            } catch (IOException | RuntimeException e) {
                resolver.delete(uri, null, null);
                throw e;
            }
            values.clear();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
            File target = new File(dir, name);
            if (target.exists()) target = new File(dir, System.currentTimeMillis() + "_" + name);
            try (OutputStream out = new FileOutputStream(target)) {
                copy(file, out);
            }
            MediaScannerConnection.scanFile(context, new String[] {target.getAbsolutePath()}, new String[] {"video/mp4"}, null);
        }
    }

    private static void copy(File from, OutputStream to) throws IOException {
        try (InputStream in = new FileInputStream(from)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) to.write(buffer, 0, n);
        }
    }

    // ------------------------------------------------------------------------------------ finding the manifest

    /**
     * The first DASH manifest text reachable from the roots (the video's props, and the full screen player's
     * command), found by value. Bounded: only the app's own objects are followed, not views or contexts.
     */
    private static String findManifest(Object[] roots) {
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        for (Object root : roots) {
            if (root != null && seen.put(root, Boolean.TRUE) == null) queue.add(new Object[] {root, 0});
        }
        int nodes = 0;
        while (!queue.isEmpty() && nodes < MAX_NODES) {
            Object[] entry = queue.poll();
            Object node = entry[0];
            int depth = (Integer) entry[1];
            nodes++;
            for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable t) {
                    continue;
                }
                for (Field f : fields) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    Object value;
                    try {
                        f.setAccessible(true);
                        value = f.get(node);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (value == null) continue;
                    if (value instanceof CharSequence) {
                        if (isManifest(value.toString())) return value.toString();
                        continue;
                    }
                    if (value instanceof Iterable) {
                        int count = 0;
                        for (Object element : (Iterable<?>) value) {
                            if (++count > 64) break;
                            if (element instanceof CharSequence && isManifest(element.toString())) return element.toString();
                        }
                        continue;
                    }
                    if (value instanceof Object[]) {
                        for (Object element : (Object[]) value) {
                            if (element instanceof CharSequence && isManifest(element.toString())) return element.toString();
                        }
                        continue;
                    }
                    if (depth < MAX_DEPTH && isAppObject(value) && seen.put(value, Boolean.TRUE) == null) {
                        queue.add(new Object[] {value, depth + 1});
                    }
                }
            }
        }
        return null;
    }

    private static boolean isManifest(String text) {
        return text.length() > 64 && text.contains("<MPD") && text.contains("BaseURL");
    }

    private static boolean isAppObject(Object value) {
        if (value instanceof View || value instanceof Context) return false;
        String name = value.getClass().getName();
        return name.startsWith("com.facebook.") || name.startsWith("X.");
    }

    /** Debug only (cache/fblite-video-debug exists): the fields of the roots, to see where a manifest is kept. */
    private static void dumpFields(Object[] roots) {
        try {
            for (Object root : roots) {
                if (root == null) continue;
                StringBuilder sb = new StringBuilder("FIELDS ").append(root.getClass().getName()).append(':');
                for (Class<?> c = root.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        Object v = f.get(root);
                        if (v == null) continue;
                        String s;
                        if (v instanceof CharSequence) {
                            String t = v.toString();
                            s = "\"" + (t.length() > 60 ? t.substring(0, 60) + "…(" + t.length() + ")" : t) + "\"";
                        } else if (v instanceof Number || v instanceof Boolean) {
                            s = v.toString();
                        } else {
                            s = v.getClass().getName();
                        }
                        sb.append(' ').append(f.getName()).append('=').append(s);
                    }
                }
                VideoFeatures.log(sb.toString(), null);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------------------------ small helpers

    private static String size(long bytes) {
        double mb = bytes / (1024.0 * 1024.0);
        return mb >= 10 ? String.format(Locale.US, "%.0f MB", mb) : String.format(Locale.US, "%.1f MB", mb);
    }

    private static boolean vi() {
        return "vi".equals(Locale.getDefault().getLanguage());
    }

    private static void toast(final Context context, final String text) {
        VideoFeatures.MAIN.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }
}

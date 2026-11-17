package io.github.zakuro.android;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ExaltDS's HD cutscenes: plays the player's own HD copy of a movie (from the Fire Emblem HD Movie Collection Blu-ray,
 * made with tools/hd-movies) over the game's 400x240 one, muted, on a window laid over the top screen.
 *
 * The game keeps playing its own movie underneath: its sound, its pace, skipping with Start, the Theater. The native
 * side (crates/zakuro-core/src/movies.rs) says which movie file the game streams and how far its picture has got, in
 * the console's own time; each display frame this shows the HD frame for that time, so the copy follows the game
 * whether emulation runs slow, fast or is paused. The copy hides as soon as the game's movie stops sending frames
 * (it ended, or Start skipped it), and a movie with no copy just plays as it always did.
 *
 * Copies are MovieOP.mp4, Movie01.mp4 … Movie13.mp4, named after the game's .moflex files, in the game's mods folder
 * under movies_hd; the sidebar imports them (as files or in ZIPs). Any video Android can decode works; the tool makes
 * them with their timing already matched to the game's.
 */
final class HdMovies implements android.view.Choreographer.FrameCallback {
    static final String FOLDER = "movies_hd";
    /** the movies Awakening has, as the PC tool names them. */
    static final int COUNT = 14;
    private static final Pattern NAME = Pattern.compile("(?i)(?:.*/)?(movie(?:op|\\d\\d))\\.mp4");
    private static final String PREFS = "exalt_hd_movies";
    /** the game's movie stopped sending frames this long ago: it ended or was skipped, so the copy goes. */
    private static final float HIDE_IDLE = 0.1f;
    /** after this long without frames (and not paused) the copy is dropped, its decoder too. */
    private static final float DROP_IDLE = 3f;
    /** a movie found but sending no frames for this long was a stray read, not a movie starting. */
    private static final float NEVER_STARTED = 5f;

    interface Source { String movie(float[] out); }
    interface Pause { boolean paused(); }

    static File dir(Context c, String id) throws IOException { return new File(GameImport.modsDir(GameImport.active(c, id), id), FOLDER); }
    static boolean enabled(Context c, String id) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(id + ".on", true); }
    static void setEnabled(Context c, String id, boolean on) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(id + ".on", on).apply(); }

    /** the copies installed, by the movie's name in lower case. */
    static Map<String, File> installed(Context c, String id) {
        Map<String, File> found = new HashMap<>();
        try {
            File[] files = dir(c, id).listFiles();
            if (files != null) for (File f : files) {
                Matcher m = NAME.matcher(f.getName());
                if (f.isFile() && f.length() > 0 && m.matches()) found.put(m.group(1).toLowerCase(Locale.ROOT), f);
            }
        } catch (IOException ignored) { }
        return found;
    }

    /** Copies the picked videos, and the videos in picked ZIPs, that are named like a movie; says what it did. */
    static String importFrom(Context c, String id, List<Uri> picked) throws IOException {
        File dir = dir(c, id);
        GameImport.mkdir(dir);
        Set<String> added = new HashSet<>();
        int skipped = 0;
        for (Uri uri : picked) {
            String name = displayName(c, uri);
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot read " + name);
                if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    try (ZipInputStream zip = new ZipInputStream(in)) {
                        for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                            Matcher m = NAME.matcher(e.getName());
                            if (e.isDirectory()) continue;
                            if (!m.matches()) { skipped++; continue; }
                            copy(zip, dir, m.group(1)); added.add(m.group(1).toLowerCase(Locale.ROOT));
                        }
                    }
                } else {
                    Matcher m = NAME.matcher(name);
                    if (!m.matches()) { skipped++; continue; }
                    copy(in, dir, m.group(1)); added.add(m.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        if (added.isEmpty()) throw new IOException("No HD cutscenes found. They are named MovieOP.mp4 and Movie01.mp4 to Movie13.mp4.");
        int total = installed(c, id).size();
        return "Imported " + added.size() + (added.size() == 1 ? " HD cutscene" : " HD cutscenes") + " (" + total + " of " + COUNT + " installed)"
                + (skipped > 0 ? "; " + skipped + " other files left out." : ".");
    }

    static void removeAll(Context c, String id) throws IOException { GameImport.remove(dir(c, id)); }

    private static void copy(InputStream in, File dir, String name) throws IOException {
        // the movie's name as the game spells it: MovieOP, Movie04
        String canonical = "Movie" + name.substring(5).toUpperCase(Locale.ROOT);
        File part = new File(dir, canonical + ".mp4.part"), done = new File(dir, canonical + ".mp4");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
            byte[] buffer = new byte[1 << 16];
            for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
        }
        for (File f : new File[]{done, new File(dir, canonical.toLowerCase(Locale.ROOT) + ".mp4")}) f.delete();
        if (!part.renameTo(done)) { part.delete(); throw new IOException("Cannot save " + done.getName()); }
    }

    private static String displayName(Context c, Uri uri) {
        try (android.database.Cursor cursor = c.getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last;
    }

    private final Game game;
    private final String id;
    private final Source source;
    private final Pause pause;
    private final float[] state = new float[12];
    private final Set<String> failed = new HashSet<>();
    private Map<String, File> files = new HashMap<>();
    private boolean running, on;
    private Showing showing;

    HdMovies(Game game, String id, Source source, Pause pause) {
        this.game = game; this.id = id; this.source = source; this.pause = pause;
        on = enabled(game, id);
        refresh();
    }

    /** Looks for installed copies again (after an import or a removal). */
    void refresh() { files = installed(game, id); failed.clear(); }
    void setOn(boolean on) { this.on = on; if (!on) drop(); }

    void start() {
        if (running) return;
        running = true;
        android.view.Choreographer.getInstance().postFrameCallback(this);
    }

    void stop() { running = false; drop(); }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        android.view.Choreographer.getInstance().postFrameCallback(this);
        if (!on || files.isEmpty()) { drop(); return; }
        String name = source.movie(state);
        if (name == null) { drop(); return; }
        int movie = (int) state[0];
        float seconds = state[1], idle = state[2];
        boolean started = state[3] > 0, paused = pause.paused();
        if ((!started && idle > NEVER_STARTED) || (started && idle > DROP_IDLE && !paused)) { drop(); return; }
        File file = files.get(name.toLowerCase(Locale.ROOT));
        if (file == null || failed.contains(file.getName())) { drop(); return; }
        android.app.Dialog presentation = game.presentation();
        boolean external = presentation != null && state[10] > 0 && state[11] > 0;
        int at = external ? 8 : 4;
        int x = (int) state[at], y = (int) state[at + 1], w = (int) state[at + 2], h = (int) state[at + 3];
        if (w <= 0 || h <= 0) { drop(); return; }
        if (showing != null && !showing.same(movie, file, external, x, y, w, h)) drop();
        if (showing == null) {
            try { showing = new Showing(movie, file, external ? presentation : null, x, y, w, h); }
            catch (RuntimeException e) { android.util.Log.w("Zakuro", "HD cutscene window", e); failed.add(file.getName()); return; }
        }
        showing.target = seconds;
        showing.show(started && (idle < HIDE_IDLE || paused));
    }

    private void drop() {
        if (showing != null) { showing.close(); showing = null; }
    }

    private void failed(Showing which, String why) {
        game.runOnUiThread(() -> {
            if (failed.add(which.file.getName())) game.showNativeMessage("HD cutscene " + which.file.getName() + " can't be played here: " + why);
            if (showing == which) drop();
        });
    }

    /** One movie's copy on screen: its window over the top screen and the thread decoding it onto that window. */
    private final class Showing implements android.view.TextureView.SurfaceTextureListener {
        private static final long FRAME_US = 33_333;
        /** further ahead than this, or behind by more, the decoder jumps instead of waiting or catching up. */
        private static final long JUMP_US = 1_000_000;

        final int movie;
        final File file;
        final boolean external;
        final int x, y, w, h;
        volatile double target;
        private volatile boolean stop;
        private final WindowManager windows;
        private final android.view.TextureView view;
        private boolean added, visible;
        private android.view.Surface surface;
        private Thread thread;

        Showing(int movie, File file, android.app.Dialog presentation, int x, int y, int w, int h) {
            this.movie = movie; this.file = file; this.external = presentation != null;
            this.x = x; this.y = y; this.w = w; this.h = h;
            Context context = external ? presentation.getContext() : game;
            windows = external ? presentation.getWindow().getWindowManager() : game.getWindowManager();
            view = new android.view.TextureView(context);
            view.setSurfaceTextureListener(this);
            // nothing until the first HD frame: the game's own picture shows through meanwhile
            view.setOpaque(false);
            view.setVisibility(View.INVISIBLE);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.graphics.PixelFormat.TRANSLUCENT);
            params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            params.x = x; params.y = y;
            params.token = (external ? presentation.getWindow() : game.getWindow()).getDecorView().getWindowToken();
            params.setTitle("ExaltDS HD cutscene");
            windows.addView(view, params);
            added = true;
        }

        boolean same(int movie, File file, boolean external, int x, int y, int w, int h) {
            return this.movie == movie && this.file.equals(file) && this.external == external && this.x == x && this.y == y && this.w == w && this.h == h;
        }

        void show(boolean on) {
            if (on == visible) return;
            visible = on;
            view.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        }

        void close() {
            stop = true;
            if (thread != null) { thread.interrupt(); try { thread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
            if (added) { try { windows.removeViewImmediate(view); } catch (RuntimeException e) { android.util.Log.w("Zakuro", "Removing the HD cutscene window", e); } added = false; }
            if (surface != null) { surface.release(); surface = null; }
        }

        @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture texture, int width, int height) {
            if (stop) return;
            surface = new android.view.Surface(texture);
            android.view.Surface target = surface;
            thread = new Thread(() -> decode(target), "exalt-hd-cutscene");
            thread.start();
        }
        @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture texture, int width, int height) { }
        @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture texture) {
            stop = true;
            if (thread != null) { thread.interrupt(); try { thread.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
            return true;
        }
        @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture texture) { }

        /** the time the game's movie is at, in the copy's own timestamps (from its first frame's). */
        private long want(long base) { return base + (long) (target * 1e6); }

        /**
         * Decodes the copy onto the window, each frame released when the game's movie reaches its time: frames that
         * are late are dropped, and a clock that ran far ahead or went back makes it jump (from the key frame before).
         */
        private void decode(android.view.Surface out) {
            MediaExtractor extractor = new MediaExtractor();
            MediaCodec codec = null;
            try {
                extractor.setDataSource(file.getPath());
                MediaFormat format = null;
                for (int i = 0; i < extractor.getTrackCount() && format == null; i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) { extractor.selectTrack(i); format = f; }
                }
                if (format == null) throw new IOException("it has no video");
                long base = Math.max(0, extractor.getSampleTime());
                codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
                codec.configure(format, out, null, 0);
                codec.start();
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                boolean inputDone = false, outputDone = false, jump = true, catchingUp = false;
                long last = -1;
                while (!stop) {
                    if (jump) {
                        extractor.seekTo(Math.max(base, want(base)), MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                        codec.flush();
                        inputDone = false; outputDone = false; jump = false; catchingUp = true; last = -1;
                    }
                    if (!inputDone) {
                        int in = codec.dequeueInputBuffer(0);
                        if (in >= 0) {
                            ByteBuffer buffer = codec.getInputBuffer(in);
                            int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                            if (size < 0) { codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                            else { codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0); extractor.advance(); }
                        }
                    }
                    if (outputDone) {
                        // past the copy's end: its last frame stays until the game's movie stops, or goes back
                        if (last >= 0 && want(base) < last - JUMP_US) jump = true; else Thread.sleep(4);
                        continue;
                    }
                    int index = codec.dequeueOutputBuffer(info, 4000);
                    if (index < 0) continue;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) { codec.releaseOutputBuffer(index, false); outputDone = true; continue; }
                    long pts = info.presentationTimeUs;
                    // wait for the game's movie to reach this frame
                    long now = want(base);
                    while (!stop && pts > now + FRAME_US / 2 && pts - now <= JUMP_US && !(last >= 0 && now < last - JUMP_US)) {
                        Thread.sleep(2);
                        now = want(base);
                    }
                    if (stop) { codec.releaseOutputBuffer(index, false); break; }
                    boolean ahead = pts - now > JUMP_US, back = last >= 0 && now < last - JUMP_US;
                    boolean behind = !catchingUp && now - pts > JUMP_US;
                    if (ahead || back || behind) { codec.releaseOutputBuffer(index, false); jump = true; continue; }
                    boolean late = pts < now - 2 * FRAME_US;
                    codec.releaseOutputBuffer(index, !late);
                    if (!late) catchingUp = false;
                    last = pts;
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                if (!stop) { android.util.Log.w("Zakuro", "HD cutscene " + file.getName(), e); failed(this, e.getMessage() == null ? e.toString() : e.getMessage()); }
            } finally {
                if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) { } codec.release(); }
                extractor.release();
            }
        }
    }
}

package io.github.zakuro.android;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;

/**
 * ExaltDS's in-game menu: a panel that slides in from the left (Back, or the controller's menu button), after Somniel's
 * sidebar. Plain Android views in a full-screen dialog over the game, which pauses while it is open.
 *
 * Live: the performance monitor. Saved at once and applied by "Apply and restart" (the game reads them at start): combat
 * frame rate, 16:9, feet, resolution, HD textures, mods and the GPU driver.
 */
final class Sidebar {
    static final int PICK_MOD = 0x5100, PICK_DRIVER = 0x5101, PICK_CODE = 0x5102, PICK_ZIP = 0x5103, EXPORT_SAVES = 0x5104, IMPORT_SAVES = 0x5105, PICK_MOVIES = 0x5106;
    private static final String[] COMBAT = {"30 fps (original)", "60 fps, original speed", "60 fps, faster"};
    private static final String[] PERF = {"Off", "FPS", "FPS + frame time", "FPS + frame time + temps"};

    private final Game game;
    private final String id;
    private final float dp;
    private Dialog dialog;
    private FrameLayout root;
    private View scrim;
    private LinearLayout panel, main, mods, drivers;
    private ScrollView scroll;
    private boolean open, handedOff, busy;
    private GameSettings s;
    /** what this run started with, to tell which changes need a restart */
    private final String started;
    private boolean modsChanged;
    private Button apply;
    private TextView modsStatus, driverStatus;
    private LinearLayout modsList;

    Sidebar(Game game, String id) {
        this.game = game; this.id = id;
        dp = game.getResources().getDisplayMetrics().density;
        s = GameSettings.load(game, id);
        started = snapshot(s);
    }

    /** The settings that need a restart; the clock is live, so it is left out. */
    private static String snapshot(GameSettings s) {
        Object clock = s.values.get("show_clock");
        s.values.put("show_clock", true);
        String out = s.json() + "|" + s.resolution + "|" + s.textures + "|" + s.driver;
        if (clock == null) s.values.remove("show_clock"); else s.values.put("show_clock", clock);
        return out;
    }

    static final String NAGA_NOTICE = "You'll receive Naga's Tear the next time you enter the world map: Robin and Chrom "
            + "find it, and Recall Fate appears in the map menu during battles.\n\n"
            + "Naga's Tear is experimental. It needs a restart: choose Apply and restart.";

    private void notice(String title, String message) {
        AlertDialog d = new AlertDialog.Builder(game, game.palette().dialogTheme()).setTitle(title).setMessage(message)
                .setPositiveButton("OK", null).create();
        d.show(); game.palette().dialog(d, false);
    }

    // ---- updates (ExaltUpdater) ----

    private void checkUpdate() {
        toast("Checking for updates…");
        new Thread(() -> {
            String error = null;
            ExaltUpdater.Release r = null;
            try { r = ExaltUpdater.latest(game); } catch (Exception e) { error = e.getMessage(); }
            ExaltUpdater.Release release = r;
            String failed = error;
            game.runOnUiThread(() -> {
                if (failed != null) { toast("Could not check: " + failed); return; }
                String current = ExaltUpdater.currentVersion(game);
                if (!ExaltUpdater.newer(release.tag, current)) { toast("ExaltDS " + current + " is the latest version."); return; }
                String notes = release.notes == null ? "" : release.notes.trim();
                if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
                AlertDialog d = new AlertDialog.Builder(game, game.palette().dialogTheme()).setTitle("ExaltDS " + release.tag + " is available")
                        .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + String.format(Locale.ROOT, "Download %.0f MB and install it? Your game, saves and settings stay.", release.size / 1048576.0))
                        .setPositiveButton("Update", (x, w) -> update(release)).setNegativeButton("Not now", null).create();
                d.show(); game.palette().dialog(d, false);
            });
        }, "exalt-update-check").start();
    }

    private void update(ExaltUpdater.Release release) {
        if (!ExaltUpdater.canInstall(game)) {
            toast("Allow ExaltDS to install apps, then choose Check for updates again.");
            ExaltUpdater.openInstallSettings(game);
            return;
        }
        new Thread(() -> {
            try {
                java.io.File apk = ExaltUpdater.download(game, release, m -> game.runOnUiThread(() -> toast(m)));
                game.runOnUiThread(() -> toast("Installing " + release.tag + "… ExaltDS closes when it is done."));
                ExaltUpdater.install(game, apk, error -> game.runOnUiThread(() -> { if (error != null) toast("The update was not installed: " + error); }));
            } catch (Exception e) {
                game.runOnUiThread(() -> toast("The update failed: " + e.getMessage()));
            }
        }, "exalt-update").start();
    }
    private int px(float v) { return Math.round(v * dp); }
    boolean isOpen() { return open; }

    // ---- open and close ----

    void open() {
        if (open || game.isFinishing()) return;
        open = true; handedOff = false;
        s = GameSettings.load(game, id);
        game.command(Game.PAUSE);
        build();
        dialog = new Dialog(game, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen);
        dialog.setContentView(root);
        dialog.setOnKeyListener((d, code, e) -> key(code, e));
        dialog.setOnDismissListener(d -> { dialog = null; if (open) { open = false; if (!handedOff) game.command(Game.RESUME); } });
        Window w = dialog.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setLayout(-1, -1);
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        w.getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        dialog.show();
        w.setDecorFitsSystemWindows(false);
        if (w.getInsetsController() != null) {
            w.getInsetsController().hide(WindowInsets.Type.systemBars());
            w.getInsetsController().setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        panel.setTranslationX(-px(360));
        panel.animate().translationX(0).setDuration(180).setListener(null).start();
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        show(main);
    }

    void dismissNow() { if (dialog != null) { handedOff = true; dialog.dismiss(); } }

    /** Closes the panel; the game resumes unless an action hands the pause to a dialog of its own. */
    void close() { close(false); }
    private void close(boolean handOff) {
        if (!open || dialog == null) return;
        handedOff = handOff;
        Dialog d = dialog;
        scrim.animate().alpha(0f).setDuration(140).start();
        panel.animate().translationX(-px(360)).setDuration(140).setListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) { d.dismiss(); }
        }).start();
    }

    private boolean key(int code, KeyEvent e) {
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) { if (up) back(); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_BUTTON_START) { if (up) close(); return true; }
        if (code == KeyEvent.KEYCODE_BUTTON_A) {
            View f = dialog == null ? null : dialog.getCurrentFocus();
            if (up && f != null) f.performClick();
            return true;
        }
        if (code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) {
            if (up) scroll.pageScroll(code == KeyEvent.KEYCODE_BUTTON_L1 ? View.FOCUS_UP : View.FOCUS_DOWN);
            return true;
        }
        return false;
    }

    private void back() { if (main.getVisibility() == View.VISIBLE) close(); else show(main); }

    private void show(LinearLayout page) {
        // back on the main page: rebuild it, so rows that depend on an import (feet, HD textures) are current
        if (page == main && main.getVisibility() != View.VISIBLE) { main.removeAllViews(); buildMain(); refreshApply(); }
        for (LinearLayout p : new LinearLayout[]{main, mods, drivers}) p.setVisibility(p == page ? View.VISIBLE : View.GONE);
        if (page == mods) refreshMods();
        if (page == drivers) refreshDrivers();
        scroll.scrollTo(0, 0);
        View first = null;
        for (int i = 0; i < page.getChildCount() && first == null; i++) {
            View v = page.getChildAt(i);
            if (v.getVisibility() == View.VISIBLE && v.isFocusable() && v.isEnabled()) first = v;
        }
        if (first != null) { View f = first; f.post(f::requestFocusFromTouch); } // the Thor stays in touch mode; plain requestFocus shows no ring
    }

    // ---- layout ----

    private void build() {
        root = new FrameLayout(game);
        scrim = new View(game);
        scrim.setBackgroundColor(0x99000000);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(game);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Exalt.PANEL_TOP, Exalt.PANEL_BOTTOM});
        float r = px(18);
        bg.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0});
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        panel.setClickable(true);
        root.addView(panel, new FrameLayout.LayoutParams(px(360), -1, Gravity.START));

        TextView title = text("ExaltDS", 22, Exalt.INK);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        String version = "";
        try { version = "  v" + game.getPackageManager().getPackageInfo(game.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        android.text.SpannableString ss = new android.text.SpannableString("ExaltDS" + version);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.55f), 7, ss.length(), 0);
        ss.setSpan(new android.text.style.ForegroundColorSpan(Exalt.MUTED), 7, ss.length(), 0);
        ss.setSpan(new android.text.style.StyleSpan(Typeface.NORMAL), 7, ss.length(), 0);
        title.setText(ss);
        title.setPadding(px(20), px(14), px(20), px(4));
        panel.addView(title);
        View accent = new View(game);
        GradientDrawable ab = new GradientDrawable();
        ab.setColor(Exalt.GOLD); ab.setCornerRadius(px(2));
        accent.setBackground(ab);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(px(32), px(3));
        alp.setMargins(px(20), px(2), 0, px(8));
        panel.addView(accent, alp);

        main = column(); mods = column(); drivers = column();
        LinearLayout pages = column();
        pages.addView(main); pages.addView(mods); pages.addView(drivers);
        scroll = new ScrollView(game);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(pages);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        buildMain(); buildMods(); buildDrivers();
        refreshApply();
    }

    private void buildMain() {
        apply = row("Apply and restart", v -> restart());
        apply.setTextColor(Exalt.GOLD);
        apply.setTypeface(Typeface.DEFAULT_BOLD);
        main.addView(apply);
        main.addView(row("Resume", v -> close()));
        main.addView(row("Quick Save", v -> { game.command(Game.SAVE); close(); }));
        main.addView(row("Quick Load", v -> { close(true); game.confirmLoad(); }));
        main.addView(row("Save to slot…", v -> { close(true); game.slotMenu(true); }));
        main.addView(row("Load from slot…", v -> { close(true); game.slotMenu(false); }));
        main.addView(row("Export saves…", v -> exportSaves()));
        main.addView(row("Import saves…", v -> importSaves()));
        main.addView(divider());

        // performance monitor: live
        TextView perfLabel = label("Performance monitor: " + PERF[Exalt.perfLevel(game)]);
        main.addView(perfLabel);
        SeekBar perf = seek(3, Exalt.perfLevel(game));
        perf.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                perfLabel.setText("Performance monitor: " + PERF[p]);
                Exalt.prefs(game).edit().putInt("perf_level", p).apply();
                game.setPerfLevel(p);
            }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { }
        });
        main.addView(perf, new LinearLayout.LayoutParams(-1, px(40)));
        // the clock in the top-right corner: live
        main.addView(check("Clock", s.flag("show_clock"), on -> {
            s.values.put("show_clock", on);
            try { s.save(game); } catch (Exception e) { toast("Could not save: " + e.getMessage()); }
            game.setShowClock(on);
            refreshApply();
        }));
        main.addView(divider());

        main.addView(label("Takes effect after Apply and restart"));
        Button combat = row("", null);
        combat.setText("Combat: " + COMBAT[Exalt.combat(s)] + "  ▾");
        combat.setOnClickListener(v -> choose(combat, COMBAT, Exalt.combat(s), i -> {
            Exalt.setCombat(s, i); save(); combat.setText("Combat: " + COMBAT[i] + "  ▾");
        }));
        main.addView(combat);
        String[] scales = {"1× (native)", "2×", "3×", "4×"};
        Button res = row("", null);
        res.setText("Resolution: " + scales[s.resolution - 1] + "  ▾");
        res.setOnClickListener(v -> choose(res, scales, s.resolution - 1, i -> {
            s.resolution = i + 1; save(); res.setText("Resolution: " + scales[i] + "  ▾");
        }));
        main.addView(res);
        main.addView(check("16:9 widescreen", Exalt.wide(s), on -> { Exalt.setWide(s, on); save(); }));
        Exalt.Layer feet = Exalt.feet(game, id);
        CheckBox feetBox = check("Feet on the ground (feet mod)", feet != null && feet.on, on -> {
            Exalt.Layer f = Exalt.feet(game, id);
            if (f != null) { Exalt.setOn(game, id, f.name, on); modsChanged = true; refreshApply(); }
        });
        if (feet == null) { feetBox.setEnabled(false); feetBox.setAlpha(0.45f); feetBox.setText("Feet mod: import it in Mods / textures"); }
        main.addView(feetBox);
        // Naga's Tear: offers the relic to campaigns that don't have it yet (Robin and Chrom's scene on the world map).
        // A campaign that has it keeps it in its save; turning this off only stops offering it.
        Exalt.Layer naga = Exalt.naga(game, id);
        CheckBox nagaBox = check("Naga's Tear (experimental)", naga != null && naga.on, on -> {
            Exalt.Layer n = Exalt.naga(game, id);
            if (n != null) { Exalt.setOn(game, id, n.name, on); modsChanged = true; refreshApply(); }
            if (on) notice("Naga's Tear", NAGA_NOTICE);
        });
        if (naga == null) { nagaBox.setEnabled(false); nagaBox.setAlpha(0.45f); nagaBox.setText("Naga's Tear: set it up from the start page"); }
        main.addView(nagaBox);
        boolean pack = hasPack();
        CheckBox hd = check("HD textures", pack && s.textures, on -> { s.textures = on; save(); });
        if (!pack) { hd.setEnabled(false); hd.setAlpha(0.45f); hd.setText("HD textures: import a pack in Mods / textures"); }
        main.addView(hd);
        // the HD cutscenes switch takes effect at once, no restart
        int movies = HdMovies.installed(game, id).size();
        CheckBox hdMovies = check("HD cutscenes", movies > 0 && HdMovies.enabled(game, id), on -> game.setHdMovies(on));
        if (movies == 0) { hdMovies.setEnabled(false); hdMovies.setAlpha(0.45f); hdMovies.setText("HD cutscenes: import them in Mods / textures"); }
        main.addView(hdMovies);
        main.addView(divider());

        // (Recall Fate is chosen from the game's own map menu; "Replace native game code" is left out for now: setup
        // builds the game code on the device)
        main.addView(row("Mods / textures…", v -> show(mods)));
        main.addView(row("GPU driver…", v -> show(drivers)));
        main.addView(row("All settings…", v -> { close(true); game.openSettings(); }));
        main.addView(divider());
        if (ExaltTelemetry.available(game))
            main.addView(check("Share anonymous diagnostics", ExaltTelemetry.enabled(game), on -> {
                ExaltTelemetry.setConsent(game, on);
                game.telemetry(on);
            }));
        if (ExaltUpdater.available(game)) main.addView(row("Check for updates", v -> checkUpdate()));
        main.addView(divider());
        main.addView(row("Restart game", v -> { close(true); game.confirmRestart(); }));
        main.addView(row("Set up the game again…", v -> { close(true); game.confirmHome(); }));
        Button quit = row("Quit ExaltDS", v -> { close(true); game.confirmQuit(); });
        quit.setTextColor(Exalt.DANGER);
        main.addView(quit);
    }

    private boolean hasPack() {
        try { return GameSettings.hasTextures(GameImport.active(game, id), id); } catch (Exception e) { return false; }
    }

    private void save() {
        try { s.save(game); } catch (Exception e) { toast("Could not save: " + e.getMessage()); }
        refreshApply();
    }

    private void refreshApply() {
        if (apply == null) return;
        boolean pending = modsChanged || !snapshot(s).equals(started);
        apply.setVisibility(pending ? View.VISIBLE : View.GONE);
    }

    /** Saves are written already; mods are linked into place, then the game starts again with the saved settings. */
    private void restart() {
        if (busy) return;
        busy = true;
        toast("Restarting…");
        boolean linkMods = modsChanged;
        new Thread(() -> {
            String error = null;
            try { if (linkMods) Exalt.apply(game, id); } catch (Exception e) { error = e.getMessage(); }
            String failed = error;
            game.runOnUiThread(() -> {
                busy = false;
                if (failed != null) { toast("Mods could not be applied: " + failed); return; }
                close(true);
                game.relaunch();
            });
        }, "exalt-apply").start();
    }

    // ---- mods and textures ----

    private void buildMods() {
        mods.addView(backRow("Mods / textures"));
        modsStatus = text("", 14, Exalt.MUTED);
        modsStatus.setPadding(px(20), px(10), px(20), px(8));
        mods.addView(modsStatus);
        modsList = column();
        mods.addView(modsList);
        mods.addView(row("Import from ZIP…", v -> pick(PICK_ZIP)));
        mods.addView(row("Import from folder…", v -> pick(PICK_MOD)));
        mods.addView(row("Remove a mod…", v -> removeMod()));
        mods.addView(row("Remove texture pack…", v -> removePack()));
        mods.addView(row("Import HD cutscenes…", v -> pickMovies()));
        mods.addView(row("Remove HD cutscenes…", v -> removeMovies()));
        TextView note = text("Choose ZIPs as downloaded (several at once is fine) or an extracted folder. A mod holds a romfs folder (like the feet mod); a texture pack holds PNG pictures and is added to the installed pack. Changes apply after Apply and restart.\n\nHD cutscenes are MovieOP.mp4 and Movie01.mp4 to Movie13.mp4, made on a PC from your own Fire Emblem HD Movie Collection Blu-ray with ExaltDS's tools/hd-movies. Choose the files, or ZIPs holding them; they play from the next cutscene, no restart.", 12, Exalt.MUTED);
        note.setPadding(px(20), px(8), px(20), px(12));
        mods.addView(note);
    }

    private void refreshMods() {
        modsList.removeAllViews();
        List<Exalt.Layer> layers = Exalt.list(game, id);
        for (Exalt.Layer l : layers)
            modsList.addView(check(l.name, l.on, on -> { Exalt.setOn(game, id, l.name, on); modsChanged = true; refreshApply(); }));
        modsStatus.setText((layers.isEmpty() ? "No mods imported." : layers.size() + (layers.size() == 1 ? " mod." : " mods."))
                + "\nHD texture pack: " + (hasPack() ? (s.textures ? "installed, on" : "installed, off") : "none")
                + "\nHD cutscenes: " + HdMovies.installed(game, id).size() + " of " + HdMovies.COUNT);
    }

    private void recallFate() {
        int[] turns = game.recallTurns();
        if (turns.length == 0) { toast("Nothing to recall yet: the start of each player phase of this battle is kept from the next one on."); return; }
        String[] names = new String[turns.length];
        for (int i = 0; i < names.length; i++) names[i] = "Turn " + turns[i] + " · Player Phase";
        AlertDialog d = new AlertDialog.Builder(game, game.palette().dialogTheme()).setTitle("Recall Fate")
                // the recall first, then a plain close, which resumes the game (a hand-off would leave it paused)
                .setItems(names, (x, i) -> { game.command(Game.RECALL | turns[i] << 4); close(); })
                .setNegativeButton("Cancel", null).create();
        d.show(); game.palette().dialog(d, false);
    }

    private void removeMod() {
        List<Exalt.Layer> layers = Exalt.list(game, id);
        if (layers.isEmpty()) { toast("No mods to remove."); return; }
        String[] names = new String[layers.size()];
        for (int i = 0; i < names.length; i++) names[i] = layers.get(i).name;
        AlertDialog d = new AlertDialog.Builder(game, game.palette().dialogTheme()).setTitle("Remove a mod")
                .setItems(names, (x, i) -> { Exalt.delete(game, id, names[i]); modsChanged = true; refreshMods(); refreshApply(); toast("Removed " + names[i] + "."); })
                .setNegativeButton("Cancel", null).create();
        d.show(); game.palette().dialog(d, false);
    }

    // ---- GPU driver ----

    private Button driverRow;

    private void buildDrivers() {
        drivers.addView(backRow("GPU driver"));
        driverRow = row("", v -> chooseDriver());
        drivers.addView(driverRow);
        drivers.addView(row("Import driver ZIP…", v -> pick(PICK_DRIVER)));
        driverStatus = text("A driver ZIP holds meta.json and the driver's .so (Turnip, for example). Only import drivers you trust: they run inside the app. The change applies after Apply and restart.", 12, Exalt.MUTED);
        driverStatus.setPadding(px(20), px(8), px(20), px(12));
        drivers.addView(driverStatus);
    }

    private List<String[]> installedDrivers() {
        List<String[]> out = new ArrayList<>();
        File[] folders = new File(game.getFilesDir(), "drivers").listFiles(f -> f.isDirectory() && f.getName().matches("[0-9a-fA-F-]{36}"));
        if (folders != null) for (File f : folders) try {
            String library = GameSettings.driverLibrary(f);
            org.json.JSONObject meta = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(new File(f, "meta.json").toPath()), java.nio.charset.StandardCharsets.UTF_8));
            out.add(new String[]{f.getName(), meta.optString("name", library) + " " + meta.optString("driverVersion", "")});
        } catch (Exception ignored) { }
        return out;
    }

    private void refreshDrivers() {
        String name = "System driver";
        if (s.flag("custom_driver")) for (String[] d : installedDrivers()) if (d[0].equals(s.driver)) name = d[1].trim();
        driverRow.setText("Driver: " + name + "  ▾");
    }

    private void chooseDriver() {
        List<String[]> list = installedDrivers();
        String[] names = new String[list.size() + 1];
        names[0] = "System driver";
        int at = 0;
        for (int i = 0; i < list.size(); i++) { names[i + 1] = list.get(i)[1].trim(); if (s.flag("custom_driver") && list.get(i)[0].equals(s.driver)) at = i + 1; }
        choose(driverRow, names, at, i -> {
            s.values.put("custom_driver", i > 0);
            if (i > 0) s.driver = list.get(i - 1)[0];
            save(); refreshDrivers();
        });
    }

    // ---- file pickers (Game forwards the results) ----

    private void pick(int request) {
        Intent intent = request == PICK_MOD
                ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                : new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(request == PICK_CODE ? "*/*" : "application/zip").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (request == PICK_ZIP) // some file managers label ZIPs differently; packs come split over several ZIPs
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/x-zip"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { game.startActivityForResult(intent, request); }
        catch (android.content.ActivityNotFoundException e) { toast("No file picker is available."); }
    }

    private void pickMovies() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/mp4", "application/zip", "application/x-zip-compressed", "application/x-zip"});
        try { game.startActivityForResult(intent, PICK_MOVIES); }
        catch (android.content.ActivityNotFoundException e) { toast("No file picker is available."); }
    }

    /** Copies the picked HD cutscenes in on a background thread; they play from the next cutscene on. */
    private void importMovies(List<Uri> uris) {
        busy = true;
        toast("Importing HD cutscenes…");
        new Thread(() -> {
            String message;
            try { message = HdMovies.importFrom(game, id, uris); }
            catch (Exception e) { message = "Import failed: " + e.getMessage(); }
            String done = message;
            game.runOnUiThread(() -> { busy = false; game.refreshHdMovies(); refreshMods(); toast(done); });
        }, "exalt-hd-movies").start();
    }

    private void removeMovies() {
        int count = HdMovies.installed(game, id).size();
        if (count == 0) { toast("No HD cutscenes installed."); return; }
        AlertDialog d = new AlertDialog.Builder(game, game.palette().dialogTheme()).setTitle("Remove HD cutscenes?")
                .setMessage("The " + count + " installed HD cutscenes are deleted; the game's own cutscenes play again.")
                .setPositiveButton("Remove", (x, w) -> {
                    try { HdMovies.removeAll(game, id); toast("HD cutscenes removed."); }
                    catch (Exception e) { toast("Could not remove them: " + e.getMessage()); }
                    game.refreshHdMovies(); refreshMods();
                })
                .setNegativeButton("Cancel", null).create();
        d.show(); game.palette().dialog(d, false);
    }

    private void exportSaves() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "ExaltDS-saves.zip");
        try { game.startActivityForResult(intent, EXPORT_SAVES); }
        catch (android.content.ActivityNotFoundException e) { toast("No file picker is available."); }
    }

    private void importSaves() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream"});
        try { game.startActivityForResult(intent, IMPORT_SAVES); }
        catch (android.content.ActivityNotFoundException e) { toast("No file picker is available."); }
    }

    /** Writes the in-game saves (the game is paused, and holds the save lock itself) to the chosen ZIP. */
    private void exportTo(Uri uri) {
        busy = true;
        toast("Exporting saves…");
        new Thread(() -> {
            String message;
            try (java.io.OutputStream out = game.getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new java.io.IOException("Cannot write the chosen file.");
                SaveArchive.exportZip(game.getFilesDir(), id, out);
                message = "Saves exported.";
            } catch (Exception e) { message = "Export failed: " + e.getMessage(); }
            String done = message;
            game.runOnUiThread(() -> { busy = false; toast(done); });
        }, "exalt-saves").start();
    }

    /** Copies the chosen ZIP aside; Game.prepareSaves restores it at the next start, before the game opens any save. */
    private void importFrom(Uri uri) {
        busy = true;
        new Thread(() -> {
            String error = null;
            try (java.io.InputStream in = game.getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Cannot read the chosen file.");
                java.io.File pending = Game.pendingSaves(game);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(pending)) {
                    byte[] buffer = new byte[65536]; long total = 0; int n;
                    while ((n = in.read(buffer)) > 0) {
                        total += n;
                        if (total > SaveArchive.MAX_ZIP_BYTES) { pending.delete(); throw new java.io.IOException("This file is too large to be a save export."); }
                        out.write(buffer, 0, n);
                    }
                }
            } catch (Exception e) { error = e.getMessage(); }
            String failed = error;
            game.runOnUiThread(() -> {
                busy = false;
                if (failed != null) { toast("Import failed: " + failed); return; }
                new android.app.AlertDialog.Builder(game).setTitle("Import saves?")
                        .setMessage("Your current in-game saves are replaced (a recovery copy is kept) and the game restarts. Save states are not touched.")
                        .setPositiveButton("Import and restart", (d, w) -> { close(true); game.relaunch(); })
                        .setNegativeButton("Cancel", (d, w) -> Game.pendingSaves(game).delete())
                        .setOnCancelListener(d -> Game.pendingSaves(game).delete()).show();
            });
        }, "exalt-saves").start();
    }

    /** True when the result was one of the sidebar's pickers. */
    boolean result(int request, int code, Intent data) {
        if (request == EXPORT_SAVES || request == IMPORT_SAVES) {
            if (code == android.app.Activity.RESULT_OK && data != null && data.getData() != null && !busy) {
                if (request == EXPORT_SAVES) exportTo(data.getData()); else importFrom(data.getData());
            }
            return true;
        }
        if (request == PICK_MOVIES) {
            if (code != android.app.Activity.RESULT_OK || data == null || busy) return true;
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            if (!picked.isEmpty()) importMovies(picked);
            return true;
        }
        if (request != PICK_MOD && request != PICK_ZIP && request != PICK_DRIVER && request != PICK_CODE) return false;
        if (code != android.app.Activity.RESULT_OK || data == null || busy) return true;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        if (uris.isEmpty()) return true;
        busy = true;
        if (request == PICK_MOD || request == PICK_ZIP) { importNext(uris, 0, request == PICK_ZIP, new ArrayList<>()); return true; }
        Uri uri = uris.get(0);
        toast(request == PICK_CODE ? "Replacing native game code…" : "Importing driver…");
        new Thread(() -> {
            String message;
            try {
                if (request == PICK_CODE) { GameImport.replaceCode(game, id, uri, (bytes, files, name) -> { }); message = "Native game code replaced. Apply and restart to use it."; }
                else { Launcher.installDriver(game, id, uri); message = "Driver imported and selected."; }
            } catch (Exception e) { message = "Import failed: " + e.getMessage(); }
            String done = message;
            game.runOnUiThread(() -> {
                busy = false;
                s = GameSettings.load(game, id); // the driver import saved its choice itself
                if (request == PICK_CODE && !done.startsWith("Import failed")) modsChanged = true; // shows Apply and restart
                toast(done);
                if (open) { refreshDrivers(); refreshApply(); }
            });
        }, "exalt-import").start();
        return true;
    }

    private void importStatus(String text) { game.runOnUiThread(() -> { if (modsStatus != null) modsStatus.setText(text); }); }
    private static String part(List<Uri> uris, int at) { return uris.size() > 1 ? " (" + (at + 1) + " of " + uris.size() + ")" : ""; }
    private GameImport.Progress progress(String part) {
        return (bytes, files, name) -> importStatus(String.format(Locale.ROOT, "Importing%s… %.1f MB, %d files", part, bytes / 1048576.0, files));
    }

    /**
     * Imports the picked folder or ZIPs one after another (a pack split over several ZIPs adds up). When a texture pack
     * has pictures for textures the installed pack already has, asks whether to overwrite those or skip them.
     */
    private void importNext(List<Uri> uris, int at, boolean zip, List<String> messages) {
        if (at >= uris.size()) {
            busy = false;
            s = GameSettings.load(game, id);
            boolean any = false;
            for (String m : messages) if (!m.startsWith("Import failed") && !m.startsWith("Skipped") && !m.startsWith("Nothing")) any = true;
            if (any) { modsChanged = true; if (hasPack() && !s.textures) { s.textures = true; save(); } }
            toast(String.join("\n", messages));
            if (open) { refreshMods(); refreshApply(); }
            return;
        }
        String part = part(uris, at);
        importStatus("Checking" + part + "…");
        new Thread(() -> {
            Exalt.Picked picked = null;
            try {
                picked = Exalt.pick(game, id, uris.get(at), zip, progress(part));
                if (picked.done != null) { messages.add(picked.done); next(uris, at, zip, messages); return; }
                Exalt.Picked pack = picked; picked = null; // closed once added or cancelled
                if (pack.pack.clashing.isEmpty()) addPack(pack, false, uris, at, zip, messages);
                else game.runOnUiThread(() -> askClashes(pack, uris, at, zip, messages));
            } catch (Exception e) {
                messages.add("Import failed" + part + ": " + e.getMessage());
                next(uris, at, zip, messages);
            } finally { if (picked != null) picked.close(); }
        }, "exalt-import").start();
    }
    private void next(List<Uri> uris, int at, boolean zip, List<String> messages) {
        game.runOnUiThread(() -> importNext(uris, at + 1, zip, messages));
    }

    /** Adds a checked texture pack on a background thread, then goes on with the next pick. */
    private void addPack(Exalt.Picked picked, boolean overwrite, List<Uri> uris, int at, boolean zip, List<String> messages) {
        String part = part(uris, at);
        importStatus("Importing" + part + "…");
        new Thread(() -> {
            try { messages.add(GameImport.addTextures(game, picked.pack, overwrite, progress(part))); }
            catch (Exception e) { messages.add("Import failed" + part + ": " + e.getMessage()); }
            finally { picked.close(); }
            next(uris, at, zip, messages);
        }, "exalt-import").start();
    }

    private void askClashes(Exalt.Picked picked, List<Uri> uris, int at, boolean zip, List<String> messages) {
        GameImport.TextureImport pack = picked.pack;
        int clashes = pack.clashing.size(), total = pack.plan.files.size();
        String name = GameImport.documentName(game.getContentResolver(), uris.get(at));
        String what = name.isEmpty() ? "This texture pack" : "“" + name + "”";
        String message = what + " has " + clashes + (clashes == 1 ? " file" : " files") + " (of " + total
                + ") for textures the installed pack already covers. The game uses one picture per texture.\n\n"
                + "Overwrite: use this pack's pictures and remove the installed ones.\n"
                + "Skip: keep the installed pictures and add only the " + (total - clashes) + " new files.";
        boolean[] chosen = {false};
        AlertDialog d = new AlertDialog.Builder(game, game.palette().dialogTheme()).setTitle("Some pictures are already installed")
                .setMessage(message)
                .setPositiveButton("Overwrite", (x, w) -> { chosen[0] = true; addPack(picked, true, uris, at, zip, messages); })
                .setNeutralButton("Skip", (x, w) -> { chosen[0] = true; addPack(picked, false, uris, at, zip, messages); })
                .setNegativeButton("Cancel", null).create();
        d.setOnDismissListener(x -> {
            if (chosen[0]) return;
            picked.close();
            messages.add("Skipped " + (name.isEmpty() ? "the texture pack" : name) + ": cancelled.");
            importNext(uris, at + 1, zip, messages);
        });
        d.show(); game.palette().dialog(d, false);
    }

    private void removePack() {
        if (!hasPack()) { toast("No texture pack is installed."); return; }
        AlertDialog d = new AlertDialog.Builder(game, game.palette().dialogTheme()).setTitle("Remove texture pack")
                .setMessage("Remove every imported HD picture? Import the pack again to get them back.")
                .setPositiveButton("Remove", (x, w) -> {
                    try { GameImport.removeTextures(game, id); s.textures = false; save(); modsChanged = true; toast("Texture pack removed."); }
                    catch (Exception e) { toast("Could not remove the pack: " + e.getMessage()); }
                    refreshMods(); refreshApply();
                })
                .setNegativeButton("Cancel", null).create();
        d.show(); game.palette().dialog(d, false);
    }

    // ---- widgets (after Somniel's skin, in ExaltDS colours) ----

    private interface Toggle { void on(boolean v); }
    private interface Choice { void pick(int i); }

    private void choose(View anchor, String[] names, int selected, Choice c) {
        PopupMenu m = new PopupMenu(game, anchor);
        for (int i = 0; i < names.length; i++) m.getMenu().add(0, i, i, (i == selected ? "● " : "    ") + names[i]);
        m.setOnMenuItemClickListener(it -> { c.pick(it.getItemId()); return true; });
        m.show();
    }

    private void toast(String t) { Toast.makeText(game, t, Toast.LENGTH_LONG).show(); }

    private LinearLayout column() { LinearLayout l = new LinearLayout(game); l.setOrientation(LinearLayout.VERTICAL); return l; }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(game); t.setText(s); t.setTextSize(sp); t.setTextColor(color); return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 13, Exalt.MUTED);
        t.setLetterSpacing(0.02f);
        t.setPadding(px(20), px(8), px(20), 0);
        return t;
    }

    private View divider() {
        View v = new View(game);
        v.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        lp.setMargins(px(20), px(6), px(20), px(6));
        v.setLayoutParams(lp);
        return v;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(42));
        lp.setMargins(px(8), px(1), px(8), px(1));
        return lp;
    }

    private StateListDrawable focusBg() {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable hi = new GradientDrawable();
        hi.setColor(Exalt.FOCUS); hi.setCornerRadius(px(10)); hi.setStroke(Math.max(1, px(1)), Exalt.GOLD_SOFT);
        GradientDrawable press = new GradientDrawable();
        press.setColor(Exalt.PRESS); press.setCornerRadius(px(10));
        d.addState(new int[]{android.R.attr.state_pressed}, press);
        d.addState(new int[]{android.R.attr.state_focused}, hi);
        d.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    private Button row(String label, View.OnClickListener l) {
        Button b = new Button(game);
        b.setText(label); b.setAllCaps(false); b.setTextSize(16);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(Exalt.INK);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(px(14), 0, px(14), 0);
        b.setStateListAnimator(null);
        b.setBackground(focusBg());
        b.setOnClickListener(l);
        b.setLayoutParams(rowParams());
        return b;
    }

    private Button backRow(String title) {
        Button b = row("‹  " + title, v -> show(main));
        b.setTypeface(Typeface.DEFAULT_BOLD);
        return b;
    }

    private CheckBox check(String label, boolean on, Toggle t) {
        CheckBox c = new CheckBox(game);
        c.setText(label); c.setTextSize(16); c.setTextColor(Exalt.INK);
        c.setButtonTintList(android.content.res.ColorStateList.valueOf(Exalt.GOLD));
        c.setChecked(on);
        c.setPadding(px(8), 0, px(14), 0);
        c.setBackground(focusBg());
        c.setOnCheckedChangeListener((b, v) -> t.on(v));
        c.setLayoutParams(rowParams());
        return c;
    }

    private SeekBar seek(int max, int value) {
        SeekBar s = new SeekBar(game);
        float r = px(3);
        GradientDrawable track = new GradientDrawable(); track.setColor(0x40FFFFFF); track.setCornerRadius(r);
        GradientDrawable fill = new GradientDrawable(); fill.setColor(Exalt.GOLD); fill.setCornerRadius(r);
        android.graphics.drawable.LayerDrawable bar = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                track, new android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)});
        bar.setId(0, android.R.id.background); bar.setId(1, android.R.id.progress);
        bar.setLayerHeight(0, px(6)); bar.setLayerHeight(1, px(6));
        bar.setLayerGravity(0, Gravity.CENTER_VERTICAL); bar.setLayerGravity(1, Gravity.CENTER_VERTICAL);
        s.setProgressDrawable(bar);
        GradientDrawable knob = new GradientDrawable(); knob.setShape(GradientDrawable.OVAL); knob.setColor(Color.WHITE); knob.setSize(px(18), px(18));
        s.setThumb(knob);
        s.setMax(max); s.setKeyProgressIncrement(1); s.setProgress(value);
        s.setPadding(px(28), px(8), px(28), px(8));
        s.setBackground(focusBg());
        return s;
    }
}

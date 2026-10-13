import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import javax.imageio.ImageIO;
import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

/**
 * PS5 front end for FreeJ2ME-Plus: a game list, a controller settings screen,
 * then the game itself. The native side shows whatever is passed to present()
 * and reports raw controller buttons through button().
 */
public class PS5Frontend {
    /* Raw controller inputs, as numbered by the native side. */
    static final int B_CROSS = 0, B_CIRCLE = 1, B_SQUARE = 2, B_TRIANGLE = 3, B_TOUCHPAD = 4, B_CREATE = 5,
        B_OPTIONS = 6, B_L3 = 7, B_R3 = 8, B_L1 = 9, B_R1 = 10, B_UP = 11, B_DOWN = 12, B_LEFT = 13,
        B_RIGHT = 14, B_L2 = 15, B_R2 = 16, S_UP = 20, S_DOWN = 21, S_LEFT = 22, S_RIGHT = 23;
    static final int[] INPUTS = { B_UP, B_DOWN, B_LEFT, B_RIGHT, B_CROSS, B_CIRCLE, B_SQUARE, B_TRIANGLE,
        B_L1, B_R1, B_L2, B_R2, B_L3, B_R3, B_OPTIONS, B_CREATE, S_UP, S_DOWN, S_LEFT, S_RIGHT };
    static final String[] INPUT_NAMES = { "D-pad up", "D-pad down", "D-pad left", "D-pad right", "Cross", "Circle",
        "Square", "Triangle", "L1", "R1", "L2", "R2", "L3", "R3", "Options", "Create",
        "Left stick up", "Left stick down", "Left stick left", "Left stick right" };

    /* Phone keys, with FreeJ2ME-Plus's own code for each (Mobile.getMobileKey); -1 is "nothing". */
    static final String[] KEY_NAMES = { "(nothing)", "Up", "Down", "Left", "Right", "Fire / OK", "Left soft key",
        "Right soft key", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "* (star)", "# (pound)" };
    static final int[] KEY_CODES = { -1, 0, 1, 2, 3, 7, 9, 8, 6, 10, 14, 11, 15, 18, 16, 5, 17, 4, 12, 13 };
    /* Default phone key (index into KEY_NAMES) for each entry of INPUTS. */
    static final int[] DEFAULT_MAP = { 1, 2, 3, 4, 5, 17, 13, 8, 6, 7, 18, 19, 9, 11, 7, 0, 10, 16, 12, 14 };

    static final int MENU_W = Ui.W, MENU_H = Ui.H;
    static final int[][] SIZES = { {240, 320}, {176, 220}, {176, 208}, {128, 160}, {128, 128}, {208, 208},
        {240, 400}, {320, 240}, {320, 480}, {352, 416}, {360, 640}, {480, 640}, {480, 800}, {640, 360}, {640, 480},
        {540, 960}, {600, 1024}, {720, 1280}, {1080, 1920}, {800, 480}, {854, 480}, {960, 540}, {1024, 600},
        {1280, 720}, {1920, 1080} };
    static final String[] SCALE_NAMES = { "Fit to screen", "Stretch to fill", "Pixel perfect" };
    /* "60 FPS" uses FreeJ2ME-Plus's frame-rate unlock: the pauses a game puts between
     * frames are removed and the game is capped at 60 frames per second instead. */
    static boolean fps60; /* per game */
    static boolean showFps;
    /* 3D drawing is done by the processor; these trade picture quality against speed. */
    static final String[] SPEED3D_NAMES = { "Off", "On (half resolution)" };
    /* What each entry means for the emulator: 0 untouched, 2 draw 3D at half size. Emulator
     * v1.52 has no other 3D quality settings; the keys for them below are ignored by it. */
    static final int[] SPEED3D_MODE = { 0, 2 };
    static int speed3d; /* per game */
    /* Picture extras, applied by the native side after the game has drawn its frame. */
    static final String[] BACKGROUND_NAMES = { "Black", "Ambient glow", "Bezel: Synthwave", "Bezel: Arcade" };
    static final String[] BEZEL_FILES = { null, null, "synthwave.jpg", "arcade.jpg" };
    static final String[] COLOUR_NAMES = { "Original", "Vivid", "Warm" };
    static final String[] SHARPEN_NAMES = { "Off", "Low", "High" };
    static final String[] EFFECT_NAMES = { "None", "Scanlines", "LCD grid", "CRT" };
    static final String[] FXAA_NAMES = { "Off", "FXAA" };
    static final String[] GHOST_NAMES = { "Off", "Light", "Strong" };
    static final String[] BRIGHT_NAMES = { "Normal", "Brighter", "Much brighter", "Brightest" };
    static int background = 1, colour, sharpen, effect, fxaa, ghosting, bright;
    /* Emulator build per game. The newer FreeJ2ME-Plus draws some 3D games correctly that
     * v1.52 does not (a 3D racing game's track), but broke at least one 2D game. Auto picks
     * the newer one for games with 3D. Switching swaps the build inside the running app (PS5Boot). */
    static final String[] ENGINE_NAMES = { "Auto", "Classic (v1.52)", "Newer build" };
    static int engine; /* per game */
    static final String runningEngine = System.getProperty("ps5.engine", "classic");
    /** Started by PS5Boot with a switchable class loader: the other emulator build loads
     *  inside this Java (main returns and PS5Boot swaps the build) instead of the app restarting. */
    static final boolean switchInPlace = "1".equals(System.getProperty("ps5.switch"));
    static volatile boolean switchEngine;
    static boolean autostarted;
    /** Set when this copy of the front end hands over to another emulator build. */
    static volatile boolean ended;
    static final java.util.HashMap<String, Boolean> has3d = new java.util.HashMap<String, Boolean>();

    static boolean wantsNewEngine(File f) {
        if (engine == 1) return false;
        if (engine == 2) return true;
        Boolean b = has3d.get(f.getName());
        if (b == null) has3d.put(f.getName(), b = GameSize.uses3D(f));
        return b;
    }
    static String engineText() {
        if (games.isEmpty()) return ENGINE_NAMES[engine];
        boolean n = wantsNewEngine(games.get(selected));
        return engine == 0 ? "Auto: " + (n ? "newer (3D game)" : "classic") : ENGINE_NAMES[engine];
    }

    /** Writes which emulator build the next start of the app should use. */
    static void writeEngine(boolean newer) {
        try { FileOutputStream o = new FileOutputStream(new File(new File(settingsFile).getParentFile(), "engine")); o.write((newer ? "new\n" : "classic\n").getBytes()); o.close(); }
        catch (Exception e) { System.out.println("j2me: could not save the emulator choice: " + e); }
    }

    static final String[] ROTATION_NAMES = { "Off", "Quarter turn right", "Upside down", "Quarter turn left" };
    static int rotation; /* per game */
    static boolean pictureScreen;
    static int pictureRow;
    static final int PICTURE_ROWS = 10;

    /** The picture extras packed the way the native side expects (see picture.h). */
    static int fx() { return (background > 0 ? 1 : 0) | colour << 1 | sharpen << 3 | effect << 5 | rotation << 7 | (background >= 2 ? 1 << 9 : 0) | fxaa << 10 | ghosting << 11 | bright << 13; }

    static String pictureLabel(int row) {
        switch (row) {
            case 0: return "Screen rotation (this game)";
            case 1: return "Size on the TV";
            case 2: return "Upscale filter";
            case 3: return "Background";
            case 4: return "Colours";
            case 5: return "Sharpen";
            case 6: return "Screen effect";
            case 7: return "Anti-aliasing";
            case 8: return "LCD ghosting";
            default: return "Brightness";
        }
    }

    static String pictureValue(int row) {
        switch (row) {
            case 0: return ROTATION_NAMES[rotation];
            case 1: return SCALE_NAMES[scaleMode];
            case 2: return FILTER_NAMES[filter];
            case 3: return BACKGROUND_NAMES[background];
            case 4: return COLOUR_NAMES[colour];
            case 5: return SHARPEN_NAMES[sharpen];
            case 6: return EFFECT_NAMES[effect];
            case 7: return FXAA_NAMES[fxaa];
            case 8: return GHOST_NAMES[ghosting];
            default: return BRIGHT_NAMES[bright];
        }
    }

    static void pictureChange(int row, int by) {
        switch (row) {
            case 0: rotation = (rotation + ROTATION_NAMES.length + by) % ROTATION_NAMES.length; break;
            case 1: scaleMode = (scaleMode + SCALE_NAMES.length + by) % SCALE_NAMES.length; break;
            case 2: filter = (filter + FILTER_NAMES.length + by) % FILTER_NAMES.length; break;
            case 3: background = (background + BACKGROUND_NAMES.length + by) % BACKGROUND_NAMES.length; break;
            case 4: colour = (colour + COLOUR_NAMES.length + by) % COLOUR_NAMES.length; break;
            case 5: sharpen = (sharpen + SHARPEN_NAMES.length + by) % SHARPEN_NAMES.length; break;
            case 6: effect = (effect + EFFECT_NAMES.length + by) % EFFECT_NAMES.length; break;
            case 7: fxaa = (fxaa + FXAA_NAMES.length + by) % FXAA_NAMES.length; break;
            case 8: ghosting = (ghosting + GHOST_NAMES.length + by) % GHOST_NAMES.length; break;
            default: bright = (bright + BRIGHT_NAMES.length + by) % BRIGHT_NAMES.length;
        }
    }

    /* Upscalers; the position in this list is the code picture.h uses (PIC_UP_*). */
    static final String[] FILTER_NAMES = { "Off", "xBRZ 2x", "xBRZ 3x", "xBRZ 4x", "xBRZ 5x", "xBRZ 6x", "Super-xBR 2x",
        "xBR 2x", "xBR 3x", "xBR 4x", "HQ2x", "HQ3x", "HQ4x", "Scale2x", "Scale3x", "Smooth (bicubic)" };

    static String gamesDir = "/data/jvm/games", settingsFile = "/data/jvm/fj/ps5-settings.properties";
    static final ArrayList<File> games = new ArrayList<File>();
    static final int[] map = DEFAULT_MAP.clone();
    static int selected, sizeIndex = -1, scaleMode, filter, controlRow; /* sizeIndex -1: detect from the game */
    static int[] detected; /* size found in the highlighted game, or null */
    static boolean controlsScreen;
    static volatile boolean inGame, startRequested, haveNative = true;
    static final Object lock = new Object();
    static boolean changed;
    static final Properties settings = new Properties();

    /** Shows a w*h picture of 0xRRGGBB pixels on the TV. Implemented in C. */
    static native void present(int[] rgb, int w, int h, int scaleMode, int filter, int fx);

    /** Draws an ARGB picture over the game (the in-game menu); null hides it. Implemented in C. */
    static native void overlay(int[] argb, int x, int y, int w, int h);
    /** Starts the app again from the launcher, which closes this one. False if that is not possible. */
    static native boolean restart();

    /** Tells the native side whether the pointer is in use. Implemented in C. */
    static native void setTouch(int on);

    static void updateTouch() {
        if (!haveNative) return;
        try { setTouch(PS5Touch.enabled && inGame && !quickMenu && !leaving ? 1 : 0); } catch (UnsatisfiedLinkError e) {}
    }

    /** Called by the native side: 0 finger down, 1 finger moved, 2 finger up, at phone-screen coordinates. */
    public static void touch(int action, int x, int y) {
        if (!inGame || quickMenu) return;
        try {
            if (action == 0) MobilePlatform.pointerPressed(x, y);
            else if (action == 1) MobilePlatform.pointerDragged(x, y);
            else MobilePlatform.pointerReleased(x, y);
        } catch (Throwable t) { System.out.println("j2me: touch: " + t); }
    }

    /** The phone frame around the game (see picture.h); nulls remove it. Implemented in C. */
    static native void frame(int[] base, int[] lit, int wx, int wy, int ww, int wh, int[] keys);
    /** Lights or unlights a key on the frame. Implemented in C. */
    static native void keyLit(int code, int on);
    static long playStart;
    static int frameSkin; /* which phone look (Ui.SKINS) */
    static volatile int frameJob;

    /** Draws the phone frame for the game's current screen size and rotation, in the background. */
    static void updatePanels() {
        if (!haveNative || !inGame) return;
        final int job = ++frameJob;
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                if (background < 2) { frame(null, null, 0, 0, 0, 0, null); return; }
                int w = Mobile.lcdWidth, h = Mobile.lcdHeight;
                if (rotation == 1 || rotation == 3) { int x = w; w = h; h = x; }
                int[] win = Ui.bezelWindow(w, h);
                BufferedImage base = Ui.bezel(BEZEL_FILES[background], win);
                if (job != frameJob || base == null) return;
                int[] px = ((DataBufferInt) base.getRaster().getDataBuffer()).getData();
                frame(px, px, win[0], win[1], win[2], win[3], new int[0]);
            } catch (UnsatisfiedLinkError e) {
            } catch (Throwable e) { System.out.println("j2me: phone frame: " + e); }
        } }, "ps5-frame");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    /** Blends the next picture in over this many ms instead of cutting to it. Implemented in C. */
    static native void fade(int ms);
    static void fadeNext(int ms) {
        if (!haveNative) return;
        try { fade(ms); } catch (UnsatisfiedLinkError e) {}
    }
    static int lastScreen = -1;

    static void setOverlay(BufferedImage img, int x, int y) {
        if (!haveNative) return;
        try {
            if (img == null) overlay(null, 0, 0, 0, 0);
            else overlay(((DataBufferInt) img.getRaster().getDataBuffer()).getData(), x, y, img.getWidth(), img.getHeight());
        } catch (UnsatisfiedLinkError e) { System.out.println("j2me: no overlay: " + e); }
    }

    static void show(int[] rgb, int w, int h) {
        if (!haveNative) return;
        try { present(rgb, w, h, scaleMode, inGame ? filter : 0, inGame ? fx() : 0); } catch (UnsatisfiedLinkError e) { haveNative = false; }
    }

    static void loadSettings() {
        try { FileInputStream in = new FileInputStream(settingsFile); settings.load(in); in.close(); } catch (Exception e) {}
        scaleMode = clamp(num("scale", 0), SCALE_NAMES.length);
        showFps = num("showfps", 0) == 1;
        background = clamp(num("background2", 2), BACKGROUND_NAMES.length);
        colour = clamp(num("colour", 0), COLOUR_NAMES.length);
        sharpen = clamp(num("sharpen", 0), SHARPEN_NAMES.length);
        effect = clamp(num("effect", 0), EFFECT_NAMES.length);
        fxaa = clamp(num("fxaa", 0), FXAA_NAMES.length);
        ghosting = clamp(num("ghost", 0), GHOST_NAMES.length);
        bright = clamp(num("bright", 0), BRIGHT_NAMES.length);
        filter = Math.max(0, Math.min(num("filter", 0), FILTER_NAMES.length - 1)); /* older builds went up to 10x */
        for (int i = 0; i < INPUTS.length; i++) map[i] = clamp(num("map." + INPUTS[i], DEFAULT_MAP[i]), KEY_NAMES.length);
    }

    static void saveSettings() {
        settings.setProperty("scale", "" + scaleMode);
        settings.setProperty("showfps", showFps ? "1" : "0");
        settings.setProperty("background2", "" + background);
        settings.setProperty("colour", "" + colour);
        settings.setProperty("sharpen", "" + sharpen);
        settings.setProperty("effect", "" + effect);
        settings.setProperty("fxaa", "" + fxaa);
        settings.setProperty("ghost", "" + ghosting);
        settings.setProperty("bright", "" + bright);
        settings.setProperty("filter", "" + filter);
        for (int i = 0; i < INPUTS.length; i++) settings.setProperty("map." + INPUTS[i], "" + map[i]);
        if (!games.isEmpty()) {
            String name = games.get(selected).getName();
            settings.setProperty("size." + name, "" + sizeIndex);
            settings.setProperty("fps60." + name, fps60 ? "1" : "0");
            settings.setProperty("opt3d." + name, "" + speed3d);
            settings.setProperty("rotate." + name, "" + rotation);
            settings.setProperty("touch." + name, PS5Touch.enabled ? "1" : "0");
            settings.setProperty("engine." + name, "" + engine);
            settings.setProperty("last", name);
        }
        try { FileOutputStream out = new FileOutputStream(settingsFile); settings.store(out, "J2ME Player for PS5"); out.close(); }
        catch (Exception e) { System.out.println("j2me: could not save settings: " + e); }
    }

    static int num(String key, int def) {
        try { return Integer.parseInt(settings.getProperty(key, "" + def).trim()); } catch (Exception e) { return def; }
    }
    static int[] parseSize(String s) {
        if (s == null) return null;
        try { String[] p = s.split("x"); return GameSize.ok(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())); }
        catch (Exception e) { return null; }
    }
    static int clamp(int v, int n) { return v < 0 || v >= n ? 0 : v; }

    /** The screen size remembered for the highlighted game, or the last one used. */
    static void recallSize() {
        if (games.isEmpty()) return;
        sizeIndex = num("size." + games.get(selected).getName(), -1);
        if (sizeIndex < -1 || sizeIndex >= SIZES.length) sizeIndex = -1;
        detected = GameSize.detect(games.get(selected));
        int[] seen = parseSize(settings.getProperty("auto." + games.get(selected).getName()));
        if (seen != null) detected = seen; /* found while the game was running (PS5Probe) */
        fps60 = num("fps60." + games.get(selected).getName(), 0) == 1;
        speed3d = clamp(num("opt3d." + games.get(selected).getName(), 0), SPEED3D_NAMES.length);
        rotation = clamp(num("rotate." + games.get(selected).getName(), 0), ROTATION_NAMES.length);
        engine = clamp(num("engine." + games.get(selected).getName(), 0), ENGINE_NAMES.length);
        /* touch: on by default for games that say they are made for a touch screen */
        PS5Touch.enabled = num("touch." + games.get(selected).getName(), Ui.info(games.get(selected)).touch ? 1 : 0) == 1;
    }

    /** The phone screen size the highlighted game will start with. */
    static int[] currentSize() {
        if (sizeIndex >= 0) return SIZES[sizeIndex];
        return detected != null ? detected : SIZES[0];
    }

    /** Called by the native side for every controller button change. */
    public static void button(int raw, boolean down) {
        /* Track L3 / R3 everywhere, so a release that arrives while no game runs
         * (for example just after L3 + R3 left one) is not missed. */
        if (raw == B_L3) l3Held = down;
        if (raw == B_R3) r3Held = down;
        if (inGame) {
            /* only the press of L3 or R3 itself completes the combination */
            if (down && (raw == B_L3 || raw == B_R3) && l3Held && r3Held && !leaving) { menuEvent(-2); return; } /* L3 + R3: back to the library */
            if (raw == B_TOUCHPAD) {
                if (down && !quickMenu) { quickMenu = true; menuEvent(-1); } /* from here on, buttons go to the menu */
                else if (down) menuEvent(B_TOUCHPAD);
                return;
            }
            if (quickMenu) { if (down) menuEvent(raw); return; }
            for (int i = 0; i < INPUTS.length; i++) {
                if (INPUTS[i] != raw) continue;
                int code = KEY_CODES[map[i]];
                if (code < 0 || code >= MobilePlatform.pressedKeys.length) return;
                MobilePlatform.pressedKeys[code] = down;
                int key = Mobile.getMobileKey(code);
                if (down) MobilePlatform.keyPressed(key); else MobilePlatform.keyReleased(key);

                return;
            }
            return;
        }
        if (raw == B_TOUCHPAD && !pictureScreen && !controlsScreen) { touchpadLibrary(down); return; }
        if (!down) return;
        boolean up = raw == B_UP || raw == S_UP, dn = raw == B_DOWN || raw == S_DOWN;
        boolean left = raw == B_LEFT || raw == S_LEFT, right = raw == B_RIGHT || raw == S_RIGHT;
        synchronized (lock) {
            if (pictureScreen) {
                if (up) pictureRow = (pictureRow + PICTURE_ROWS - 1) % PICTURE_ROWS;
                else if (dn) pictureRow = (pictureRow + 1) % PICTURE_ROWS;
                else if (left) pictureChange(pictureRow, -1);
                else if (right || raw == B_CROSS) pictureChange(pictureRow, 1);
                else if (raw == B_SQUARE) { rotation = 0; scaleMode = 0; filter = 0; background = 2; colour = 0; sharpen = 0; effect = 0; fxaa = 0; ghosting = 0; bright = 0; }
                else if (raw == B_CIRCLE || raw == B_TRIANGLE) { pictureScreen = false; saveSettings(); }
            } else if (controlsScreen) {
                int n = INPUTS.length, k = KEY_NAMES.length;
                if (up) controlRow = (controlRow + n - 1) % n;
                else if (dn) controlRow = (controlRow + 1) % n;
                else if (left) map[controlRow] = (map[controlRow] + k - 1) % k;
                else if (right || raw == B_CROSS) map[controlRow] = (map[controlRow] + 1) % k;
                else if (raw == B_SQUARE) System.arraycopy(DEFAULT_MAP, 0, map, 0, n);
                else if (raw == B_CIRCLE || raw == B_TRIANGLE) { controlsScreen = false; saveSettings(); }
            } else if (deleteAsk >= 0) { /* "Delete this game?" */
                int rows = deleteRows();
                if (up) deleteAsk = (deleteAsk + rows - 1) % rows;
                else if (dn) deleteAsk = (deleteAsk + 1) % rows;
                else if (raw == B_CROSS) {
                    int row = deleteAsk;
                    deleteAsk = -1;
                    if (row < rows - 1 && !games.isEmpty()) deleteGame(games.get(selected), row == 1);
                }
                else if (raw == B_CIRCLE) deleteAsk = -1;
            } else {
                int n = games.size();
                if (up && n > 0) { selected = (selected + n - 1) % n; recallSize(); }
                else if (dn && n > 0) { selected = (selected + 1) % n; recallSize(); }
                else if (left || right) {
                    sizeIndex = left ? (sizeIndex <= -1 ? SIZES.length - 1 : sizeIndex - 1) : (sizeIndex >= SIZES.length - 1 ? -1 : sizeIndex + 1);
                    if (n > 0) { /* back on Auto: check again; a size picked by hand is left alone */
                        if (sizeIndex == -1) settings.remove("probed." + games.get(selected).getName());
                        else settings.setProperty("probed." + games.get(selected).getName(), "1");
                    }
                }
                else if (raw == B_SQUARE || raw == B_CIRCLE) pictureScreen = true;
                else if (raw == B_L1) PS5Touch.enabled = !PS5Touch.enabled;
                else if (raw == B_R1) filter = (filter + 1) % FILTER_NAMES.length; /* shortcut */
                else if (raw == B_L2) fps60 = !fps60;
                else if (raw == B_L3) showFps = !showFps;
                else if (raw == B_R2) engine = (engine + 1) % ENGINE_NAMES.length;
                else if (raw == B_R3) speed3d = (speed3d + 1) % SPEED3D_NAMES.length;
                else if (raw == B_TRIANGLE) controlsScreen = true;
                else if (raw == B_CROSS && n > 0) { saveSettings(); startRequested = true; }
            }
            changed = true;
            lock.notifyAll();
        }
    }

    static BufferedImage drawMenu() { return Ui.draw(); }

    /** Reads the games folder again (new or removed games), keeping the highlighted game. */
    static void rescanGames(boolean manual) {
        String keep = games.isEmpty() ? settings.getProperty("last") : games.get(selected).getName();
        ArrayList<File> found = new ArrayList<File>();
        File[] list = new File(gamesDir).listFiles();
        if (list != null) {
            Arrays.sort(list);
            for (File f : list) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && (n.endsWith(".jar") || n.endsWith(".jad") || n.endsWith(".kjx"))) found.add(f);
            }
        }
        int before = games.size();
        games.clear();
        games.addAll(found);
        selected = 0;
        for (int i = 0; keep != null && i < games.size(); i++) if (games.get(i).getName().equals(keep)) selected = i;
        if (!games.isEmpty()) recallSize();
        if (manual || games.size() != before) {
            refreshNote = games.size() == before ? "Game list is up to date" : "Game list refreshed: " + games.size() + " games";
            refreshUntil = System.currentTimeMillis() + 3000;
        }
        System.out.println("j2me: " + games.size() + " games in " + gamesDir + " (was " + before + ")");
    }
    static String refreshNote;
    static long refreshUntil;

    /* ---- the touchpad in the library: press to refresh the list, hold to delete the game ---- */
    static final long HOLD_MS = 700;
    static volatile int touchpadPress; /* counts presses, so a timer only acts on its own press */
    static volatile boolean touchpadHeld, touchpadUsed;

    static void touchpadLibrary(boolean down) {
        if (down) {
            if (touchpadHeld) return;
            touchpadHeld = true;
            touchpadUsed = false;
            final int press = ++touchpadPress;
            Thread t = new Thread(new Runnable() { public void run() {
                try { Thread.sleep(HOLD_MS); } catch (InterruptedException e) { return; }
                synchronized (lock) {
                    if (!touchpadHeld || press != touchpadPress || touchpadUsed) return;
                    touchpadUsed = true;
                    if (deleteAsk < 0 && !games.isEmpty() && !pictureScreen && !controlsScreen && !inGame) {
                        deleteAsk = deleteRows() - 1; /* starts on Cancel */
                        changed = true;
                        lock.notifyAll();
                    }
                }
            } }, "ps5-touchpad-hold");
            t.setDaemon(true);
            t.start();
            return;
        }
        synchronized (lock) {
            boolean shortPress = touchpadHeld && !touchpadUsed;
            touchpadHeld = false;
            touchpadUsed = true;
            if (!shortPress) return;
            if (deleteAsk >= 0) deleteAsk = -1; /* a tap closes the delete dialog */
            else rescanGames(true);
            changed = true;
            lock.notifyAll();
        }
    }

    /* ---- deleting a game from the library (hold the touchpad) ---- */
    /** Highlighted row of the delete dialog, or -1 when it is closed. */
    static int deleteAsk = -1;
    static final String[] GAME_KEYS = { "auto.", "engine.", "fps60.", "map.", "opt3d.", "probed.", "rotate.", "size.", "touch." };

    /** Rows: "Delete game", then "Delete game and saves" when it has saves, then "Cancel". */
    static int deleteRows() { return games.isEmpty() || saveFolders(games.get(selected)).isEmpty() ? 2 : 3; }

    /** The emulator's save (rms) and settings (config) folders for a game. FreeJ2ME-Plus names
     *  them after the game's MIDlet-Name without colons, or its file name with dots as underscores.
     *  A folder shared with another game in the library (same name) is left out. */
    static ArrayList<File> saveFolders(File game) {
        ArrayList<String> names = new ArrayList<String>();
        names.add(game.getName().replace('.', '_'));
        String t = Ui.info(game).title.replace(":", "").trim();
        if (t.length() > 0 && !names.contains(t)) names.add(t);
        for (File other : games) {
            if (other.equals(game)) continue;
            names.remove(Ui.info(other).title.replace(":", "").trim());
        }
        ArrayList<File> out = new ArrayList<File>();
        for (String n : names)
            for (String dir : new String[] { "rms", "config" }) {
                File d = new File(dir, n);
                if (d.isDirectory()) out.add(d);
            }
        return out;
    }

    static boolean removeTree(File f) {
        File[] in = f.listFiles();
        if (in != null) for (File c : in) removeTree(c);
        return f.delete();
    }

    /** Deletes the game file and its settings in this app; with saves, also its save and config folders. */
    static void deleteGame(File game, boolean saves) {
        String name = game.getName(), title = Ui.info(game).title;
        ArrayList<File> folders = saves ? saveFolders(game) : new ArrayList<File>();
        boolean ok = game.delete() || !game.exists();
        System.out.println("j2me: deleting " + game + (ok ? "" : " FAILED") + (saves ? ", saves " + folders : ""));
        if (!ok) {
            refreshNote = "Could not delete " + title;
            refreshUntil = System.currentTimeMillis() + 4000;
            return;
        }
        for (File d : folders) removeTree(d);
        for (String k : GAME_KEYS) settings.remove(k + name);
        has3d.remove(name);
        Ui.cache.remove(game);
        if (name.equals(settings.getProperty("last"))) settings.remove("last");
        int at = selected;
        rescanGames(false);
        selected = games.isEmpty() ? 0 : Math.min(at, games.size() - 1);
        if (!games.isEmpty()) recallSize();
        saveSettings();
        refreshNote = "Deleted " + title + (saves && !folders.isEmpty() ? " and its saves" : "");
        refreshUntil = System.currentTimeMillis() + 3000;
    }

    /* ---- in-game menu: the touchpad opens it, L3 + R3 goes back to the library ---- */
    static volatile boolean quickMenu, l3Held, r3Held, leaving;
    static int quickRow;
    static String quickNote;
    static final int QUICK_ROWS = PICTURE_ROWS + 5; /* Resume, phone screen, touch, the picture rows, emulator, Return to library */
    static int startedSize; /* sizeIndex the running game was started with */

    static String sizeText() {
        int[] cs = currentSize();
        return (sizeIndex < 0 ? "Auto: " : "") + cs[0] + " x " + cs[1];
    }
    static final java.util.concurrent.LinkedBlockingQueue<Integer> menuEvents = new java.util.concurrent.LinkedBlockingQueue<Integer>();
    static Thread menuThread;

    static String quickLabel(int row) {
        if (row == 0) return "Resume game";
        if (row == 1) return "Phone screen (this game)";
        if (row == 2) return "Touch screen (this game)";
        if (row == QUICK_ROWS - 1) return "Return to game library";
        if (row == QUICK_ROWS - 2) return "Emulator (this game)";
        return pictureLabel(row - 3);
    }
    static String quickValue(int row) {
        if (row == 1) return sizeText();
        if (row == 2) return PS5Touch.enabled ? "On: right stick + R2" : "Off";
        if (row == QUICK_ROWS - 2) return engineText();
        return row == 0 || row == QUICK_ROWS - 1 ? null : pictureValue(row - 3);
    }

    /** Menu work runs on its own thread so the picture and controller never wait for it. */
    static void menuEvent(int raw) {
        synchronized (menuEvents) {
            if (menuThread == null) {
                menuThread = new Thread(new Runnable() {
                    public void run() {
                        while (!ended) {
                            try { menuAction(menuEvents.take()); }
                            catch (ThreadDeath | InterruptedException t) { return; } /* this emulator build is being dropped */
                            catch (Throwable t) { if (ended) return; System.out.println("j2me: menu: " + t); }
                        }
                    }
                }, "ps5-menu");
                menuThread.setDaemon(true);
                menuThread.start();
            }
        }
        menuEvents.add(raw);
    }

    /** Lets go of every phone key, so nothing stays held while the menu is open. */
    static void releaseKeys() {
        for (int i = 0; i < INPUTS.length; i++) {
            int code = KEY_CODES[map[i]];
            if (code < 0 || code >= MobilePlatform.pressedKeys.length || !MobilePlatform.pressedKeys[code]) continue;
            MobilePlatform.pressedKeys[code] = false;
            try { MobilePlatform.keyReleased(Mobile.getMobileKey(code)); } catch (Throwable t) {}
            if (haveNative) { try { keyLit(code, 0); } catch (Throwable t) {} }
        }
    }

    static void pauseGame(boolean pause) {
        try { if (Mobile.isPaused != pause) MobilePlatform.pauseResumeApp(); }
        catch (Throwable t) { System.out.println("j2me: pause/resume: " + t); }
    }

    /** Shows the last game picture again with the current settings (the game may be paused). */
    static void repaintGame() {
        try {
            int[] d = Mobile.getPlatform().getLcdFrontbuffer().getDataBuffer();
            BufferedImage c = Mobile.getPlatform().getLcdFrontbuffer().getCanvas();
            show(d, c.getWidth(), c.getHeight());
        } catch (Throwable t) {}
    }

    static void drawQuick() {
        lastQuick = Ui.quick();
        setOverlay(lastQuick, 0, 0);
    }
    static BufferedImage lastQuick;

    static void closeQuick() {
        quickMenu = false;
        quickNote = null;
        setOverlay(null, 0, 0);
        updateTouch();
        updatePanels();
        saveSettings();
        pauseGame(false);
    }

    static void menuAction(int raw) {
        if (leaving) return;
        if (raw == -2) { backToLibrary(); return; }
        if (raw == -1) {
            updateTouch();
            releaseKeys();
            quickRow = 0;
            quickNote = null;
            pauseGame(true);
            drawQuick();
            return;
        }
        if (!quickMenu) return;
        boolean up = raw == B_UP || raw == S_UP, dn = raw == B_DOWN || raw == S_DOWN;
        boolean left = raw == B_LEFT || raw == S_LEFT, right = raw == B_RIGHT || raw == S_RIGHT;
        boolean picRow = quickRow > 2 && quickRow < QUICK_ROWS - 2;
        quickNote = null;
        if (up) quickRow = (quickRow + QUICK_ROWS - 1) % QUICK_ROWS;
        else if (dn) quickRow = (quickRow + 1) % QUICK_ROWS;
        else if (quickRow == 1 && (left || right || raw == B_CROSS)) {
            sizeIndex = left ? (sizeIndex <= -1 ? SIZES.length - 1 : sizeIndex - 1) : (sizeIndex >= SIZES.length - 1 ? -1 : sizeIndex + 1);
            PS5Probe.active = false; /* the player decides now */
            settings.setProperty("probed." + games.get(selected).getName(), "1");
            int[] cs = currentSize();
            resizeLive(cs[0], cs[1]);
            quickNote = "The game now has a " + cs[0] + " x " + cs[1] + " screen. If it does not look right yet, resume and give it a moment.";
        }
        else if (quickRow == 2 && (left || right || raw == B_CROSS)) {
            PS5Touch.enabled = !PS5Touch.enabled;
            quickNote = PS5Touch.enabled ? "Touch on: the right stick moves a pointer over the game and R2 touches. Some games only check for touch when they start; if this one does not react, return to the library and start it again."
                                         : "Touch off: R2 and the right stick are buttons again.";
        }
        else if (picRow && (left || right || raw == B_CROSS)) { pictureChange(quickRow - 3, left ? -1 : 1); repaintGame(); }
        else if (raw == B_CROSS && quickRow == 0) { closeQuick(); return; }
        else if (raw == B_CROSS && quickRow == QUICK_ROWS - 1) { backToLibrary(); return; }
        else if (quickRow == QUICK_ROWS - 2 && (left || right)) {
            engine = (engine + (left ? ENGINE_NAMES.length - 1 : 1)) % ENGINE_NAMES.length;
            boolean differs = wantsNewEngine(games.get(selected)) != runningEngine.equals("new");
            quickNote = differs ? "Press Cross to start the game again with this emulator." : "This game is already running on that emulator.";
        }
        else if (quickRow == QUICK_ROWS - 2 && raw == B_CROSS) {
            boolean newer = wantsNewEngine(games.get(selected));
            if (newer == runningEngine.equals("new")) quickNote = "This game is already running on that emulator.";
            else if (switchInPlace) {
                writeEngine(newer);
                settings.setProperty("autostart", games.get(selected).getName());
                switchEngine = true;
                backToLibrary();
                quickNote = "Switching to the " + (newer ? "newer" : "classic") + " emulator...";
                drawQuick();
                return;
            }
            else { writeEngine(newer); restartWith("Restarting with the " + (newer ? "newer" : "classic") + " emulator...", games.get(selected).getName()); return; }
        }
        else if (raw == B_CIRCLE || raw == B_TOUCHPAD || raw == B_OPTIONS) { closeQuick(); return; }
        else return;
        drawQuick();
    }

    /** Back to the game library, staying in the app: the main loop ends the game (see unloadGame). */
    static void backToLibrary() {
        releaseKeys();
        saveSettings();
        quickMenu = true;
        quickNote = "Returning to the game library...";
        leaving = true;
        drawQuick();
        System.out.println("j2me: going back to the game library");
        exitRequested = true;
    }

    static volatile boolean exitRequested;
    static java.util.Set<Thread> threadsBefore = new java.util.HashSet<Thread>();
    /** Stops the given threads (JVMTI StopThread). Implemented in C. */
    static native void stopThreads(Thread[] threads, Throwable reason);

    /** Waits for the game to end: L3 + R3, the quick menu, or the game quitting itself. */
    static void waitForExit() throws InterruptedException {
        while (!exitRequested) {
            if (MobilePlatform.appTerminated) { System.out.println("j2me: the game closed itself"); break; }
            Thread.sleep(150);
        }
    }

    /** Ends the game and leaves the emulator ready for another, without restarting the app. */
    static void unloadGame() {
        long t0 = System.currentTimeMillis();
        leaving = true;
        PS5Probe.active = false;
        inGame = false;
        updateTouch();
        try { if (haveNative) frame(null, null, 0, 0, 0, 0, null); } catch (UnsatisfiedLinkError e) {}
        try {
            BufferedImage l = Ui.loading(switchEngine ? "Switching emulator..." : "Back to the library...");
            setOverlay(null, 0, 0);
            fadeNext(260);
            show(((DataBufferInt) l.getRaster().getDataBuffer()).getData(), MENU_W, MENU_H);
        } catch (Throwable t) {}
        PS5Unload.destroyMidlet(1500);
        PS5Unload.stopSound();
        int left = PS5Unload.stopThreads(threadsBefore);
        PS5Unload.reset();
        quickMenu = false;
        quickNote = null;
        l3Held = r3Held = false;
        pictureScreen = controlsScreen = false;
        startRequested = false;
        rescanGames(false); /* games may have been added over FTP while playing */
        lastScreen = 3; /* the library comes back with a full fade */
        exitRequested = false;
        leaving = false;
        System.gc();
        Runtime r = Runtime.getRuntime();
        System.out.println("j2me: game ended in " + (System.currentTimeMillis() - t0) + " ms, " + left + " threads left, memory used " + (r.totalMemory() - r.freeMemory()) / 1048576 + " MB");
    }

    /** Gets this emulator build ready to be dropped by PS5Boot: its sound is closed and
     *  nothing more is drawn from this copy of the front end. */
    static void endForSwitch() {
        System.out.println("j2me: switching emulator build inside the app");
        ended = true;
        try { PS5Unload.closeSound(); } catch (Throwable t) {}
        try { setOverlay(null, 0, 0); } catch (Throwable t) {}
        try { setTouch(0); } catch (Throwable t) {}
    }

    /** Starts the app again; with a game name, that game starts straight away instead of the library. */
    static void restartWith(String note, String autostart) {
        releaseKeys();
        if (autostart != null) settings.setProperty("autostart", autostart); else settings.remove("autostart");
        saveSettings();
        leaving = true;
        updateTouch();
        if (note != null) { quickMenu = true; quickNote = note; drawQuick(); }
        System.out.println("j2me: going back to the game library");
        if (haveNative) {
            boolean ok;
            try { ok = restart(); } catch (UnsatisfiedLinkError e) { ok = false; }
            if (ok) return; /* the launcher closes this app shortly */
        }
        settings.remove("autostart");
        saveSettings();
        quickNote = "Could not restart: the ELF loader on port 9021 is not running. Hold OPTIONS to quit.";
        quickMenu = true;
        drawQuick();
        leaving = false;
    }

    /** Applies FreeJ2ME-Plus's own settings (frame limit, phone type, sound, backlight tint)
     *  while keeping the screen size chosen in this app. */
    static void applySettings(int w, int h) {
        try {
            Mobile.config.settings.put("scrwidth", "" + w);
            Mobile.config.settings.put("scrheight", "" + h);
            Mobile.config.settings.put("fpshack", fps60 ? "Safe" : "Disabled");
            if (fps60) Mobile.config.settings.put("fps", "60");
            Mobile.config.sysSettings.put("fpsCounterPosition", showFps ? "TopLeft" : "Off");
            /* "app" leaves each effect up to the game; "on" forces it for every surface. */
            int mode = SPEED3D_MODE[speed3d];
            String filters = mode == 3 ? "on" : mode == 0 ? "app" : "off", half = mode == 2 ? "on" : "off";
            Mobile.config.settings.put("m3gantialiasmode", filters);
            Mobile.config.settings.put("m3gbilinearmode", filters);
            Mobile.config.settings.put("m3gditheringmode", filters);
            Mobile.config.settings.put("m3gperspcorrmode", mode == 3 ? "on" : "app");
            Mobile.config.settings.put("m3gmipmapmode", mode == 3 ? "linear" : filters);
            Mobile.config.settings.put("m3gperspcorrsubfactor", mode == 3 ? "extra" : mode == 0 ? "high" : "low");
            Mobile.config.settings.put("spdhackm3ghalfres", half);
            Mobile.config.settings.put("spdhackmcv3halfres", half);
            Mobile.updateSettings();

        } catch (Throwable t) {
            System.out.println("j2me: could not apply emulator settings: " + t);
            Mobile.maskIndex = 0; /* at least never leave the green backlight tint on */
        }
    }

    /** Auto screen size: watches the game's first 45 seconds and, if it was clearly made for
     *  another screen, remembers that size and starts the game again with it (once per game). */
    static void watchSize(final String name, final int w, final int h) {
        PS5Probe.reset();
        PS5Probe.active = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int sec = 1; sec <= 45; sec++) {
                        Thread.sleep(1000);
                        if (leaving) return;
                        int[] s = PS5Probe.verdict(w, h, sec % 10 == 0);
                        if (s == null) continue;
                        PS5Probe.active = false;
                        PS5Probe.verdict(w, h, true);
                        System.out.println("j2me: game draws for " + s[0] + "x" + s[1] + ", not " + w + "x" + h + "; resizing the screen to fit");
                        settings.setProperty("auto." + name, s[0] + "x" + s[1]);
                        settings.setProperty("probed." + name, "1");
                        detected = s;
                        sizeIndex = -1; /* Auto, now using the size found */
                        saveSettings();
                        resizeLive(s[0], s[1]);
                        if (!quickMenu) {
                            banner("Screen size found: " + s[0] + " x " + s[1]);
                            Thread.sleep(2500);
                            if (!quickMenu && !leaving) setOverlay(null, 0, 0);
                        }
                        return;
                    }
                    PS5Probe.verdict(w, h, true);
                    System.out.println("j2me: screen size " + w + "x" + h + " looks right");
                    synchronized (lock) { settings.setProperty("probed." + name, "1"); }
                    saveSettings();
                } catch (ThreadDeath e) { /* the game was closed */
                } catch (Throwable e) { System.out.println("j2me: size check: " + e); }
                finally { PS5Probe.active = false; }
            }
        }, "ps5-size-check");
        t.setDaemon(true);
        t.start();
    }

    /** Gives the running game a phone screen of another size, the way the emulator's
     *  libretro core does when its resolution option changes. Games written for a fixed
     *  size simply carry on drawing; ones that follow the screen size get sizeChanged(). */
    static void resizeLive(int w, int h) {
        try {
            applySettings(w, h);
            Mobile.getPlatform().resizeLCD(Mobile.lcdWidth, Mobile.lcdHeight);
            updatePanels();
            System.out.println("j2me: screen resized to " + Mobile.lcdWidth + "x" + Mobile.lcdHeight + " while running");
            final javax.microedition.lcdui.Displayable d = Mobile.getDisplay().getCurrent();
            if (d instanceof javax.microedition.lcdui.Canvas) { /* on its own thread: it can wait while the game is paused */
                Thread t = new Thread(new Runnable() { public void run() { try { ((javax.microedition.lcdui.Canvas) d).repaint(); } catch (Throwable e) {} } });
                t.setDaemon(true);
                t.start();
            }
            Thread.sleep(150);
        } catch (Throwable t) { System.out.println("j2me: resize failed: " + t); }
        repaintGame();
    }

    static String bannerText;
    static boolean touchTest;
    static void banner(String text) {
        bannerText = text;
        BufferedImage b = Ui.banner(text);
        setOverlay(b, (Ui.W - b.getWidth()) / 2, 60);
    }

    static void startGame(final File jar, final int w, final int h) {
        System.out.println("j2me: emulator " + runningEngine);
        System.out.println("j2me: starting " + jar + " at " + w + "x" + h + ", rotation " + ROTATION_NAMES[rotation] + ", picture " + SCALE_NAMES[scaleMode] + ", touch " + (PS5Touch.enabled ? "on" : "off") + ", upscale " + FILTER_NAMES[filter] + ", background " + background + ", colours " + colour + ", sharpen " + sharpen + ", effect " + effect);
        Ui.forget(); /* the menu's pictures are not needed while playing */
        PS5Media.prepare(); /* normally done already; sound threads must exist before the snapshot below */
        threadsBefore = new java.util.HashSet<Thread>(Thread.getAllStackTraces().keySet());
        Mobile.clearOldLog();
        MobilePlatform.isLibretro = true;
        Mobile.setPlatform(new MobilePlatform(w, h), new Runnable() { public void run() { applySettings(w, h); } });
        Mobile.getPlatform().setPainter(new Runnable() {
            public void run() {
                int[] d = Mobile.getPlatform().getLcdFrontbuffer().getDataBuffer();
                BufferedImage c = Mobile.getPlatform().getLcdFrontbuffer().getCanvas();
                show(d, c.getWidth(), c.getHeight());
            }
        });
        if (!Mobile.getPlatform().load(jar.toURI().toString())) {
            System.out.println("j2me: could not load " + jar);
            return;
        }
        applySettings(w, h);
        System.out.println("j2me: emulator settings applied, tint " + Mobile.maskIndex + ", frame limit " + Mobile.limitFPS + ", 60 FPS " + (fps60 ? "on" : "off") + ", counter " + (showFps ? "on" : "off") + ", 3D options " + SPEED3D_NAMES[speed3d] + ", sound " + Mobile.sound);
        inGame = true;
        updateTouch();
        playStart = System.currentTimeMillis();
        updatePanels();
        if (PS5Touch.enabled) {
            new Thread(new Runnable() { public void run() {
                try {
                    Thread.sleep(1500);
                    banner("Touch screen: right stick moves the pointer, R2 touches");
                    Thread.sleep(4000);
                    if (!quickMenu && !leaving && bannerText != null && bannerText.startsWith("Touch")) setOverlay(null, 0, 0);
                } catch (Exception e) {}
            } }, "ps5-touch-tip").start();
        }
        String name = jar.getName();
        /* Every game is checked once, including ones whose size was picked by hand in an older version. */
        if (settings.getProperty("probed." + name) == null) watchSize(name, w, h);
        fadeNext(400); /* from the loading screen into the game's first picture */
        Mobile.getPlatform().runJar();
        System.out.println("j2me: game running");
    }

    public static void main(String[] args) throws Exception {
        boolean selfTest = args.length > 0 && args[0].equals("--selftest");
        if (selfTest) {
            gamesDir = args[1]; settingsFile = args[2] + "/ps5-settings.properties"; haveNative = false;
            String lib = System.getProperty("ps5.hostlib"); /* stopThreads for testing on a PC */
            if (lib != null) try { System.load(lib); } catch (UnsatisfiedLinkError e) { System.out.println("j2me: " + e.getMessage()); }
        }
        ImageIO.setUseCache(false);
        final Thread.UncaughtExceptionHandler dflt = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() { /* a stopped game thread is not an error */
            public void uncaughtException(Thread t, Throwable e) {
                if (e instanceof ThreadDeath) return;
                if (dflt != null) dflt.uncaughtException(t, e); else { System.err.print("Exception in thread \"" + t.getName() + "\" "); e.printStackTrace(); }
            }
        });
        File[] list = new File(gamesDir).listFiles();
        if (list != null) {
            Arrays.sort(list);
            for (File f : list) {
                String n = f.getName().toLowerCase();
                if (f.isFile() && (n.endsWith(".jar") || n.endsWith(".jad") || n.endsWith(".kjx"))) games.add(f);
            }
        }
        loadSettings();
        String last = settings.getProperty("last");
        for (int i = 0; last != null && i < games.size(); i++) if (games.get(i).getName().equals(last)) selected = i;
        recallSize();
        String auto = settings.getProperty("autostart");
        if (auto != null) { /* restarted from the in-game menu to change the phone screen size */
            settings.remove("autostart");
            if (!games.isEmpty() && games.get(selected).getName().equals(auto)) startRequested = autostarted = true;
        }
        System.out.println("j2me: " + games.size() + " games in " + gamesDir);
        if (!selfTest) PS5Media.warmUp(); /* the slowest part of starting a game, done while choosing */
        if (selfTest) {
            button(B_TRIANGLE, true); button(B_DOWN, true); button(S_DOWN, true); button(B_RIGHT, true);
            ImageIO.write(drawMenu(), "png", new File(args[2], "controls.png"));
            button(B_SQUARE, true); button(B_CIRCLE, true);
            button(B_DOWN, true); button(B_UP, true);
            ImageIO.write(drawMenu(), "png", new File(args[2], "menu.png"));
            button(B_SQUARE, true); button(B_DOWN, true); button(B_DOWN, true); button(B_RIGHT, true); button(B_DOWN, true); button(B_RIGHT, true);
            ImageIO.write(drawMenu(), "png", new File(args[2], "picture.png"));
            button(B_CIRCLE, true);
            button(B_CROSS, true);
        }
        boolean firstRound = true;
        while (true) { /* library, game, library, ... */
        while (true) {
            if (firstRound && startRequested && !selfTest) break; /* started with a game to run (emulator switch): no library in between */
            BufferedImage m = drawMenu();
            int screen = pictureScreen ? 1 : controlsScreen ? 2 : 0;
            if (lastScreen >= 0) fadeNext(screen != lastScreen ? 240 : 110); /* a soft change between screens and rows */
            lastScreen = screen;
            show(((DataBufferInt) m.getRaster().getDataBuffer()).getData(), MENU_W, MENU_H);
            if (!selfTest && !changed && !pictureScreen && !controlsScreen) {
                try { Ui.warm(); } catch (Throwable t) { System.out.println("j2me: warm: " + t); }
            }
            synchronized (lock) {
                if (startRequested) break;
                if (!changed) lock.wait(selfTest ? 10 : 2000);
                changed = false;
            }
        }
        saveSettings();
        if (!games.isEmpty() && haveNative) {
            boolean newer = wantsNewEngine(games.get(selected));
            if (newer != runningEngine.equals("new") && !(firstRound && autostarted)) { /* never switch twice in a row */
                System.out.println("j2me: " + games.get(selected).getName() + " needs the " + (newer ? "newer" : "classic") + " emulator; restarting the app");
                writeEngine(newer);
                settings.setProperty("autostart", games.get(selected).getName());
                saveSettings();
                BufferedImage l = Ui.loading("Switching to the " + (newer ? "newer" : "classic") + " emulator...");
                fadeNext(260);
                show(((DataBufferInt) l.getRaster().getDataBuffer()).getData(), MENU_W, MENU_H);
                if (switchInPlace) { endForSwitch(); return; } /* PS5Boot loads the other build and comes back with this game */
                boolean ok;
                try { ok = restart(); } catch (UnsatisfiedLinkError e) { ok = false; }
                if (ok) { while (true) Thread.sleep(1000); } /* the launcher closes this app */
                settings.remove("autostart");
                saveSettings();
                System.out.println("j2me: could not restart; starting on the current emulator");
            }
        }
        try { /* something on screen straight away while the game gets going */
            BufferedImage l = Ui.loading("Starting...");
            fadeNext(260);
            show(((DataBufferInt) l.getRaster().getDataBuffer()).getData(), MENU_W, MENU_H);
            if (selfTest) ImageIO.write(l, "png", new File(args[2], "loading.png"));
        } catch (Throwable t) { System.out.println("j2me: loading screen: " + t); }
        startedSize = sizeIndex;
        startGame(games.get(selected), currentSize()[0], currentSize()[1]);
        if (selfTest && firstRound) {
            Thread.sleep(2500);
            button(B_SQUARE, true); button(B_SQUARE, false); Thread.sleep(300);
            ImageIO.write(Mobile.getPlatform().getLcdFrontbuffer().getCanvas(), "png", new File(args[2], "game.png"));
            button(B_TOUCHPAD, true); button(B_TOUCHPAD, false); button(B_DOWN, true); button(B_RIGHT, true); button(B_DOWN, true); button(B_DOWN, true); button(B_RIGHT, true);
            button(B_DOWN, true); button(B_DOWN, true); button(B_DOWN, true); button(B_DOWN, true); button(B_RIGHT, true);
            button(B_UP, true); button(B_UP, true); button(B_UP, true); button(B_UP, true); button(B_UP, true); button(B_RIGHT, true);
            Thread.sleep(1500);
            System.out.println("j2me: menu open " + quickMenu + ", paused " + Mobile.isPaused + ", rotation " + rotation + ", colours " + colour + ", lcd " + Mobile.getPlatform().getLcdFrontbuffer().getCanvas().getWidth() + "x" + Mobile.getPlatform().getLcdFrontbuffer().getCanvas().getHeight());
            BufferedImage q = new BufferedImage(Ui.W, Ui.H, BufferedImage.TYPE_INT_RGB);
            Graphics2D qg = q.createGraphics();
            qg.drawImage(Mobile.getPlatform().getLcdFrontbuffer().getCanvas(), 1100, 200, null);
            qg.drawImage(lastQuick, 0, 0, null); qg.dispose();
            ImageIO.write(q, "png", new File(args[2], "quick.png"));
            for (int[] sz : new int[][] { {240, 320}, {320, 240}, {176, 208} }) {
                int[] win = Ui.frameWindow(sz[0], sz[1]);
                BufferedImage f = Ui.phoneFrame(Ui.selectedAccent(), win, false, null), fl = Ui.phoneFrame(Ui.selectedAccent(), win, true, null);
                Graphics2D fg = f.createGraphics();
                fg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                fg.drawImage(Mobile.getPlatform().getLcdFrontbuffer().getCanvas(), win[0], win[1], win[2], win[3], null);
                fg.drawImage(fl.getSubimage(win[0] + win[2] + 200, win[1] + win[3] / 2 - 60, 120, 120), win[0] + win[2] + 200, win[1] + win[3] / 2 - 60, null); /* one lit key */
                fg.dispose();
                ImageIO.write(f, "png", new File(args[2], "frame-" + sz[0] + "x" + sz[1] + ".png"));
            }
            button(B_RIGHT, true); Thread.sleep(300);
            System.out.println("j2me: touch now " + PS5Touch.enabled + ", note: " + quickNote);
            touchTest = true;
            button(B_CIRCLE, true); Thread.sleep(500);
            touch(0, 120, 160); touch(1, 130, 170); touch(2, 130, 170); Thread.sleep(300);
            System.out.println("j2me: menu open " + quickMenu + ", paused " + Mobile.isPaused);
            button(B_L3, true); button(B_R3, true); Thread.sleep(500); button(B_L3, false); button(B_R3, false);
            System.out.println("j2me: after L3+R3: " + quickNote);
            System.out.println("j2me: canvas touch support = " + new javax.microedition.lcdui.Canvas() { public void paint(javax.microedition.lcdui.Graphics g) {} }.hasPointerEvents());
        }
        if (selfTest && !firstRound) {
            Thread.sleep(2000);
            ImageIO.write(Mobile.getPlatform().getLcdFrontbuffer().getCanvas(), "png", new File(args[2], "game2.png"));
            exitRequested = true;
        }
        waitForExit();
        unloadGame();
        if (selfTest && firstRound && switchInPlace && runningEngine.equals("classic")) { /* PC test of the in-app emulator switch */
            writeEngine(true);
            settings.setProperty("autostart", games.get(selected).getName());
            switchEngine = true;
        }
        if (switchEngine) { saveSettings(); endForSwitch(); return; }
        if (selfTest) {
            ImageIO.write(drawMenu(), "png", new File(args[2], firstRound ? "menu-after.png" : "menu-after2.png"));
            if (!firstRound) { System.out.println("j2me: selftest done"); Runtime.getRuntime().halt(0); }
            button(B_DOWN, true); button(B_CROSS, true); /* a second game, to show the emulator is reusable */
        }
        firstRound = false;
        }
    }
}

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Draws every screen of the player at 1920x1080: the game library, picture settings,
 * controller settings, and the in-game quick menu and notices.
 *
 * Look: a dark backdrop lit by two soft glows in the highlighted game's own colour
 * (taken from its icon), a faint grid of dots like an old phone LCD, frosted-glass
 * cards, and PlayStation-coloured button symbols. Everything slow (the backdrop)
 * is drawn once per colour and kept, because Java here runs without a JIT.
 */
public class Ui {
    static final int W = 1920, H = 1080, ROWS = 7;

    /* ---- colours ---- */
    static final Color INK = new Color(0x07080f), TEXT = new Color(0xf4f5fb), DIM = new Color(0x9aa0b8),
        FAINT = new Color(0x5d6380), GLASS = new Color(255, 255, 255, 14), GLASS_HI = new Color(255, 255, 255, 26),
        EDGE = new Color(255, 255, 255, 30), EDGE_SOFT = new Color(255, 255, 255, 16),
        PS_CROSS = new Color(0x7fa8ff), PS_CIRCLE = new Color(0xff6b7d), PS_SQUARE = new Color(0xf08fd0),
        PS_TRIANGLE = new Color(0x4fd6b0), DEFAULT_ACCENT = new Color(0x7c5cff);

    /* ---- type: Poppins, bundled in the jar; the system sans-serif if it cannot be loaded ---- */
    static Font regular, medium, bold;
    static final HashMap<String, Font> fonts = new HashMap<String, Font>();

    static Font load(String name) {
        try {
            InputStream in = Ui.class.getResourceAsStream("/fonts/" + name);
            if (in != null) try { return Font.createFont(Font.TRUETYPE_FONT, in); } finally { in.close(); }
        } catch (Exception e) { System.out.println("j2me: font " + name + ": " + e); }
        return null;
    }

    static Font font(int size, int weight) { /* weight 0 regular, 1 medium, 2 bold */
        if (regular == null) {
            regular = load("Poppins-Regular.ttf");
            medium = load("Poppins-Medium.ttf");
            bold = load("Poppins-Bold.ttf");
            if (regular == null) regular = new Font("SansSerif", Font.PLAIN, 12);
            if (medium == null) medium = regular;
            if (bold == null) bold = new Font("SansSerif", Font.BOLD, 12);
        }
        String k = weight + ":" + size;
        Font f = fonts.get(k);
        if (f == null) fonts.put(k, f = (weight == 2 ? bold : weight == 1 ? medium : regular).deriveFont((float) size));
        return f;
    }
    /** Kept for older callers: bold means the heaviest weight. */
    static Font font(int size, boolean b) { return font(size, b ? 2 : 0); }

    /* ---- what the library shows for one game, read from the jar the first time ---- */
    static class Info {
        String title, vendor = "", version = "";
        boolean touch; /* the game says it is made for a touch screen */
        BufferedImage icon;
        Color accent;
        long bytes;
    }
    static final HashMap<File, Info> cache = new HashMap<File, Info>();

    static Info info(File f) {
        Info i = cache.get(f);
        if (i != null) return i;
        i = new Info();
        i.bytes = f.length();
        String n = f.getName();
        i.title = n.substring(0, n.lastIndexOf('.') < 0 ? n.length() : n.lastIndexOf('.')).replace('_', ' ');
        if (n.toLowerCase().endsWith(".jar")) {
            try {
                ZipFile z = new ZipFile(f);
                try {
                    String icon = null;
                    ZipEntry mf = z.getEntry("META-INF/MANIFEST.MF");
                    if (mf != null) {
                        BufferedReader r = new BufferedReader(new InputStreamReader(z.getInputStream(mf), "UTF-8"));
                        String line;
                        while ((line = r.readLine()) != null) {
                            int c = line.indexOf(':');
                            if (c < 0) continue;
                            String k = line.substring(0, c).trim().toLowerCase(), v = line.substring(c + 1).trim();
                            if (k.equals("midlet-name") && v.length() > 0) i.title = v;
                            else if (k.equals("midlet-vendor")) i.vendor = v;
                            else if (k.equals("midlet-version")) i.version = v;
                            else if (k.equals("midlet-icon") && v.length() > 0) icon = v;
                            else if (k.equals("midlet-touch-support") && v.toLowerCase().startsWith("t")) i.touch = true;
                            else if (k.equals("nokia-midlet-on-screen-keypad") && v.toLowerCase().startsWith("no")) i.touch = true;
                            else if (k.equals("midlet-1") && icon == null) {
                                String[] p = v.split(",");
                                if (p.length > 1 && p[1].trim().length() > 0) icon = p[1].trim();
                            }
                        }
                    }
                    if (icon != null) {
                        ZipEntry e = z.getEntry(icon.startsWith("/") ? icon.substring(1) : icon);
                        if (e != null) i.icon = ImageIO.read(z.getInputStream(e));
                    }
                } finally { z.close(); }
            } catch (Exception e) { /* keep the file name as the title */ }
        }
        i.accent = accentOf(i);
        cache.put(f, i);
        return i;
    }

    /** The game's colour: the strongest colour in its icon, or one picked from its name. */
    static Color accentOf(Info i) {
        float hue = -1;
        if (i.icon != null) {
            double sx = 0, sy = 0, weight = 0;
            int w = i.icon.getWidth(), h = i.icon.getHeight(), step = Math.max(1, Math.max(w, h) / 24);
            float[] hsb = new float[3];
            for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) {
                int p = i.icon.getRGB(x, y);
                if ((p >>> 24) < 128) continue;
                Color.RGBtoHSB(p >> 16 & 255, p >> 8 & 255, p & 255, hsb);
                double wt = hsb[1] * hsb[2] * hsb[1]; /* vivid pixels count most */
                sx += Math.cos(hsb[0] * 2 * Math.PI) * wt;
                sy += Math.sin(hsb[0] * 2 * Math.PI) * wt;
                weight += wt;
            }
            if (weight > 0.5) hue = (float) ((Math.atan2(sy, sx) / (2 * Math.PI) + 1) % 1);
        }
        if (hue < 0) hue = (i.title.hashCode() & 0xffff) / 65536f;
        hue = (Math.round(hue * 24) % 24) / 24f; /* 24 shades, so pictures made for one can be reused */
        return Color.getHSBColor(hue, 0.62f, 1f);
    }

    static Color selectedAccent() {
        if (PS5Frontend.games.isEmpty()) return DEFAULT_ACCENT;
        return info(PS5Frontend.games.get(PS5Frontend.selected)).accent;
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }
    static Color mix(Color a, Color b, double t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t), (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }
    static Color shiftHue(Color c, float by) {
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        return Color.getHSBColor((hsb[0] + by + 1) % 1, hsb[1], hsb[2]);
    }

    /* ---- the backdrop: worked out at 1/8 size, then enlarged smoothly and dotted ---- */
    static final Map<Integer, BufferedImage> backdrops = new LinkedHashMap<Integer, BufferedImage>(4, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Integer, BufferedImage> e) { return size() > 4; }
    };

    static BufferedImage backdrop(Color accent) {
        BufferedImage b = backdrops.get(accent.getRGB());
        if (b != null) return b;
        int sw = W / 16, sh = H / 16;
        Color second = shiftHue(accent, 0.12f);
        BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                double u = (double) x / sw, v = (double) y / sh;
                /* base: near-black, a touch lighter at the top */
                double r = 9 - v * 4, g = 10 - v * 4, bl = 20 - v * 8;
                /* glow 1: the game's colour, up and to the left */
                double d1 = sq(u - 0.18) * 2.2 + sq(v + 0.05) * 3.2, k1 = Math.exp(-d1 * 3.2) * 0.42;
                /* glow 2: a neighbouring colour, low on the right */
                double d2 = sq(u - 0.92) * 2.0 + sq(v - 1.05) * 3.0, k2 = Math.exp(-d2 * 3.0) * 0.30;
                r += accent.getRed() * k1 + second.getRed() * k2;
                g += accent.getGreen() * k1 + second.getGreen() * k2;
                bl += accent.getBlue() * k1 + second.getBlue() * k2;
                /* vignette */
                double vig = 1 - 0.55 * (sq(u - 0.5) + sq(v - 0.5));
                px[y * sw + x] = clamp(r * vig) << 16 | clamp(g * vig) << 8 | clamp(bl * vig);
            }
        }
        small.setRGB(0, 0, sw, sh, px, 0, sw);
        b = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(small, 0, 0, W, H, null);
        g.drawImage(dots(), 0, 0, null);
        /* a fine light line under the header */
        g.setPaint(new GradientPaint(0, 0, alpha(accent, 0), W / 2, 0, alpha(accent, 70), true));
        g.fillRect(0, 132, W, 1);
        g.dispose();
        backdrops.put(accent.getRGB(), b);
        return b;
    }
    /* Pieces that look the same every time (glows, the phone, the page around the list)
     * are drawn once into a picture and reused; only text and icons are drawn each time. */
    interface Painter { void paint(Graphics2D g); }
    static final Map<String, BufferedImage> pieces = new LinkedHashMap<String, BufferedImage>(32, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 40; }
    };
    static BufferedImage piece(String key, int w, int h, Painter p) {
        BufferedImage b = pieces.get(key);
        if (b == null) {
            b = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = smooth(b);
            p.paint(g);
            g.dispose();
            pieces.put(key, b);
        }
        return b;
    }

    static BufferedImage dotLayer;
    /** Phone-LCD dots, strongest near the top and fading out; made once. */
    static BufferedImage dots() {
        if (dotLayer != null) return dotLayer;
        dotLayer = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[W * H];
        for (int y = 18; y < H; y += 28) {
            int a = (int) (16 * (1 - (double) y / H));
            if (a < 2) break;
            int c = a << 24 | 0xffffff;
            for (int x = 18; x < W; x += 28) { px[y * W + x] = c; px[y * W + x + 1] = c; px[(y + 1) * W + x] = c; px[(y + 1) * W + x + 1] = c; }
        }
        dotLayer.setRGB(0, 0, W, H, px, 0, W);
        return dotLayer;
    }

    /** Frees the menu pictures (tens of MB) for the game. */
    static void forget() { pieces.clear(); backdrops.clear(); dotLayer = null; }

    static double sq(double d) { return d * d; }
    static int clamp(double v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }

    /* ---- building blocks ---- */
    static Graphics2D smooth(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        return g;
    }

    static String fit(Graphics2D g, String s, int width) {
        FontMetrics m = g.getFontMetrics();
        if (m.stringWidth(s) <= width) return s;
        while (s.length() > 1 && m.stringWidth(s + "...") > width) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    static int text(Graphics2D g, String s, int x, int y, int size, int weight, Color c) {
        g.setFont(font(size, weight)); g.setColor(c); g.drawString(s, x, y);
        return x + g.getFontMetrics().stringWidth(s);
    }

    static int width(Graphics2D g, String s, int size, int weight) {
        g.setFont(font(size, weight)); return g.getFontMetrics().stringWidth(s);
    }

    /** A frosted card: a light veil, a hairline edge and a brighter top edge. */
    static void glass(Graphics2D g, int x, int y, int w, int h, int r, Color fill) {
        g.setColor(fill); g.fillRoundRect(x, y, w, h, r, r);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(EDGE_SOFT); g.drawRoundRect(x, y, w, h, r, r);
        g.setColor(new Color(255, 255, 255, 22)); g.drawLine(x + r / 2, y + 1, x + w - r / 2, y + 1);
    }

    /** A highlighted row: tinted glass, a soft glow around it and a bar on the left. */
    static void highlight(Graphics2D g, int x, int y, int w, int h, final int r, final Color accent) {
        final int fw = w, fh = h;
        g.drawImage(piece("hl" + accent.getRGB() + ":" + w + "x" + h + ":" + r, w + 32, h + 32, new Painter() {
            public void paint(Graphics2D p) { highlightNow(p, 16, 16, fw, fh, r, accent); }
        }), x - 16, y - 16, null);
    }
    static void highlightNow(Graphics2D g, int x, int y, int w, int h, int r, Color accent) {
        for (int i = 4; i >= 1; i--) {
            g.setColor(alpha(accent, 10 + (4 - i) * 6));
            g.setStroke(new BasicStroke(i * 3f));
            g.drawRoundRect(x, y, w, h, r, r);
        }
        g.setPaint(new GradientPaint(x, y, alpha(accent, 70), x + w, y, alpha(accent, 22)));
        g.fillRoundRect(x, y, w, h, r, r);
        g.setStroke(new BasicStroke(2f)); g.setColor(alpha(mix(accent, Color.WHITE, 0.4), 160)); g.drawRoundRect(x, y, w, h, r, r);
        g.setColor(mix(accent, Color.WHITE, 0.35)); g.fillRoundRect(x + 10, y + h / 2 - 18, 5, 36, 5, 5);
    }

    /** A pill showing a setting; filled in the accent when it is on. */
    static int chip(Graphics2D g, String s, int x, int y, boolean on, Color accent) {
        int w = width(g, s, 20, 1) + 36, h = 42;
        if (on) { g.setColor(alpha(accent, 60)); g.fillRoundRect(x, y, w, h, h, h); g.setColor(alpha(accent, 200)); }
        else { g.setColor(GLASS); g.fillRoundRect(x, y, w, h, h, h); g.setColor(EDGE); }
        g.setStroke(new BasicStroke(1.5f)); g.drawRoundRect(x, y, w, h, h, h);
        text(g, s, x + 18, y + 29, 20, 1, on ? TEXT : DIM);
        return x + w + 12;
    }

    /** A controller symbol: the four face buttons in their colours, others as a small key cap. Returns its width. */
    static int glyph(Graphics2D g, String b, int x, int y, int size) {
        int cx = x + size / 2, cy = y + size / 2, s = size * 2 / 9;
        if (b.equals("X") || b.equals("O") || b.equals("S") || b.equals("T")) {
            Color c = b.equals("X") ? PS_CROSS : b.equals("O") ? PS_CIRCLE : b.equals("S") ? PS_SQUARE : PS_TRIANGLE;
            g.setColor(new Color(255, 255, 255, 20)); g.fillOval(x, y, size, size);
            g.setStroke(new BasicStroke(1.5f)); g.setColor(EDGE); g.drawOval(x, y, size, size);
            g.setColor(c); g.setStroke(new BasicStroke(size / 13f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (b.equals("X")) { g.drawLine(cx - s, cy - s, cx + s, cy + s); g.drawLine(cx + s, cy - s, cx - s, cy + s); }
            else if (b.equals("O")) g.drawOval(cx - s - 1, cy - s - 1, 2 * s + 2, 2 * s + 2);
            else if (b.equals("S")) g.drawRect(cx - s, cy - s, 2 * s, 2 * s);
            else g.drawPolygon(new int[] { cx, cx + s + 2, cx - s - 2 }, new int[] { cy - s - 1, cy + s, cy + s }, 3);
            return size;
        }
        if (b.equals("<>")) { /* left / right */
            int w = size + 22;
            g.setColor(new Color(255, 255, 255, 24)); g.fillRoundRect(x, y, w, size, 14, 14);
            g.setStroke(new BasicStroke(1.5f)); g.setColor(EDGE); g.drawRoundRect(x, y, w, size, 14, 14);
            g.setColor(TEXT);
            int my = y + size / 2, a = size / 6;
            g.fillPolygon(new int[] { x + 12, x + 12 + a + 2, x + 12 + a + 2 }, new int[] { my, my - a, my + a }, 3);
            g.fillPolygon(new int[] { x + w - 12, x + w - 14 - a, x + w - 14 - a }, new int[] { my, my - a, my + a }, 3);
            return w;
        }
        int w = width(g, b, size * 10 / 22, 2) + size * 2 / 3;
        g.setColor(new Color(255, 255, 255, 24)); g.fillRoundRect(x, y, w, size, 14, 14);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(EDGE); g.drawRoundRect(x, y, w, size, 14, 14);
        text(g, b, x + size / 3, y + size * 2 / 3 + 1, size * 10 / 22, 2, TEXT);
        return w;
    }

    /** "symbol + what it does" at the bottom of a screen. Returns the next x. */
    static int hint(Graphics2D g, String button, String what, int x, int y) {
        x += glyph(g, button, x, y, 44) + 12;
        return text(g, what, x, y + 30, 22, 0, DIM) + 40;
    }

    static void icon(Graphics2D g, Info i, int x, int y, int size, int radius) {
        g.setColor(alpha(i.accent, 40)); g.fillRoundRect(x, y, size, size, radius, radius);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(alpha(i.accent, 90)); g.drawRoundRect(x, y, size, size, radius, radius);
        if (i.icon != null) {
            int iw = i.icon.getWidth(), ih = i.icon.getHeight(), pad = size / 7;
            double s = Math.min((size - 2.0 * pad) / iw, (size - 2.0 * pad) / ih);
            if (s >= 2) s = Math.floor(s);
            int dw = (int) (iw * s), dh = (int) (ih * s);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(i.icon, x + (size - dw) / 2, y + (size - dh) / 2, dw, dh, null);
        } else {
            String c = i.title.length() > 0 ? i.title.substring(0, 1).toUpperCase() : "?";
            g.setFont(font(size / 2, 2));
            int cw = g.getFontMetrics().stringWidth(c);
            text(g, c, x + (size - cw) / 2, y + size * 2 / 3 + size / 24, size / 2, 2, mix(i.accent, Color.WHITE, 0.55));
        }
    }

    /** The small logo: a phone with a lit screen, then the name. */
    static void logo(Graphics2D g, Color accent, int x, int y) {
        g.setColor(new Color(0x1a1d2e)); g.fillRoundRect(x, y, 34, 56, 10, 10);
        g.setStroke(new BasicStroke(2f)); g.setColor(alpha(mix(accent, Color.WHITE, 0.3), 220)); g.drawRoundRect(x, y, 34, 56, 10, 10);
        g.setPaint(new GradientPaint(x, y + 8, mix(accent, Color.WHITE, 0.25), x, y + 30, accent)); g.fillRoundRect(x + 6, y + 9, 22, 22, 4, 4);
        g.setColor(new Color(255, 255, 255, 120));
        for (int r = 0; r < 2; r++) for (int c = 0; c < 3; c++) g.fillRect(x + 7 + c * 8, y + 37 + r * 7, 4, 3);
        int nx = text(g, "J2ME", x + 54, y + 44, 40, 2, TEXT);
        text(g, " Player", nx, y + 44, 40, 0, alpha(TEXT, 210));
    }

    static BufferedImage page(BufferedImage img, Color accent, String title, String right, boolean done) {
        page(img, accent, title, right).dispose();
        return img;
    }

    /** At most three full-screen pieces are kept (each is 8 MB). */
    static void trimFullScreen() {
        int big = 0;
        java.util.ArrayList<String> drop = new java.util.ArrayList<String>();
        java.util.ArrayList<Map.Entry<String, BufferedImage>> all = new java.util.ArrayList<Map.Entry<String, BufferedImage>>(pieces.entrySet());
        for (int i = all.size() - 1; i >= 0; i--) if (all.get(i).getValue().getWidth() == W && ++big > 3) drop.add(all.get(i).getKey());
        for (String k : drop) pieces.remove(k);
    }

    static Graphics2D page(BufferedImage img, Color accent, String title, String right) {
        Graphics2D g = smooth(img);
        g.drawImage(backdrop(accent), 0, 0, null);
        header(g, accent, title, right);
        return g;
    }

    static void header(Graphics2D g, Color accent, String title, String right) {
        if (title == null) logo(g, accent, 72, 50);
        else {
            g.setColor(mix(accent, Color.WHITE, 0.3)); g.fillRoundRect(72, 62, 8, 40, 8, 8);
            text(g, title, 100, 98, 42, 2, TEXT);
        }
        if (right != null) {
            int w = width(g, right, 22, 1) + 40;
            glass(g, W - 72 - w, 60, w, 46, 46, GLASS);
            text(g, right, W - 72 - w + 20, 91, 22, 1, DIM);
        }
    }

    /* ---- screens ---- */
    static BufferedImage draw() {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        if (PS5Frontend.pictureScreen) picture(img); else if (PS5Frontend.controlsScreen) controls(img); else library(img);
        if (PS5Frontend.deleteAsk >= 0 && !PS5Frontend.pictureScreen && !PS5Frontend.controlsScreen && !PS5Frontend.games.isEmpty())
            deleteDialog(img);
        return img;
    }

    static String kb(long b) { return b >= 1048576 ? String.format("%.1f MB", b / 1048576.0) : (b + 1023) / 1024 + " KB"; }

    static void library(BufferedImage img) {
        int n = PS5Frontend.games.size(), sel = PS5Frontend.selected;
        Color accent = selectedAccent();
        final String count = n == 1 ? "1 game" : n + " games";
        final Color ac = accent;
        final boolean any = n > 0;
        Graphics2D g = smooth(img);
        g.drawImage(backdrop(accent), 0, 0, null);
        /* everything that does not change while moving through the list, drawn once */
        g.drawImage(piece("lib" + count, W, H, new Painter() {
            public void paint(Graphics2D p) {
                header(p, DEFAULT_ACCENT, null, count);
                text(p, "LIBRARY", 72, 172, 20, 1, FAINT);
                if (any) glass(p, 1110, 160, W - 72 - 1110, 812, 40, GLASS);
                int hx = 72, hy = H - 80;
                hx = hint(p, "X", "Play", hx, hy);
                hx = hint(p, "S", "Picture", hx, hy);
                hx = hint(p, "T", "Controls", hx, hy);
                hx = hint(p, "OPTIONS", "Hold to quit", hx, hy);
                hx = hint(p, "TOUCHPAD", "Refresh  ·  hold: Delete", hx, hy);
                String tip = "In game:  touchpad menu  ·  L3+R3 library";
                if (hx + 20 < W - 72 - width(p, tip, 20, 0)) text(p, tip, W - 72 - width(p, tip, 20, 0), hy + 30, 20, 0, FAINT);
            }
        }), 0, 0, null);
        int lx = 72, ly = 196, lw = 980, rh = 100, gap = 10;
        if (PS5Frontend.refreshNote != null && System.currentTimeMillis() < PS5Frontend.refreshUntil) {
            String note = PS5Frontend.refreshNote;
            int nw = width(g, note, 22, 1) + 48;
            glass(g, W / 2 - nw / 2, 60, nw, 46, 46, alpha(accent, 60));
            text(g, note, W / 2 - nw / 2 + 24, 91, 22, 1, TEXT);
        }

        if (n == 0) {
            glass(g, lx, ly, W - 144, 360, 36, GLASS);
            g.setColor(alpha(accent, 50)); g.fillRoundRect(lx + 56, ly + 64, 120, 92, 20, 20);
            g.setColor(alpha(accent, 140)); g.fillRoundRect(lx + 56, ly + 50, 56, 26, 12, 12);
            text(g, "No games yet", lx + 220, ly + 104, 44, 2, TEXT);
            text(g, "Copy .jar or .jad files to this folder on the console over FTP (port 2121),", lx + 220, ly + 160, 26, 0, DIM);
            text(g, "then start the player again.", lx + 220, ly + 198, 26, 0, DIM);
            glass(g, lx + 220, ly + 236, 520, 64, 20, GLASS_HI);
            text(g, PS5Frontend.gamesDir, lx + 248, ly + 278, 28, 1, mix(accent, Color.WHITE, 0.5));
        }
        int first = Math.max(0, Math.min(sel - ROWS / 2, n - ROWS));
        for (int i = first; i < n && i < first + ROWS; i++) {
            int y = ly + (i - first) * (rh + gap);
            Info in = info(PS5Frontend.games.get(i));
            boolean on = i == sel;
            if (on) highlight(g, lx, y, lw, rh, 26, in.accent);
            icon(g, in, lx + 30, y + 14, 72, 20);
            g.setFont(font(30, on ? 2 : 1));
            text(g, fit(g, in.title, lw - 340), lx + 128, y + 50, 30, on ? 2 : 1, on ? TEXT : alpha(TEXT, 215));
            g.setFont(font(20, 0));
            String sub = in.vendor.length() > 0 ? in.vendor : PS5Frontend.games.get(i).getName();
            text(g, fit(g, sub, lw - 340), lx + 128, y + 80, 20, 0, on ? alpha(TEXT, 190) : FAINT);
            String size = kb(in.bytes);
            text(g, size, lx + lw - 32 - width(g, size, 20, 0), y + 58, 20, 0, on ? alpha(TEXT, 190) : FAINT);
        }
        if (n > ROWS) { /* scroll position */
            int th = ROWS * (rh + gap) - gap, bh = Math.max(48, th * ROWS / n), by = ly + (th - bh) * first / (n - ROWS);
            g.setColor(new Color(255, 255, 255, 18)); g.fillRoundRect(lx + lw + 18, ly, 6, th, 6, 6);
            g.setColor(alpha(accent, 200)); g.fillRoundRect(lx + lw + 18, by, 6, bh, 6, 6);
        }
        if (n > 0) hero(g, info(PS5Frontend.games.get(sel)), 1110, 160, W - 72 - 1110, 812);
        g.dispose();
    }

    /** "Delete this game?" over the library: the game, what goes with it, and the choices. */
    static void deleteDialog(BufferedImage img) {
        java.io.File f = PS5Frontend.games.get(PS5Frontend.selected);
        Info in = info(f);
        int rows = PS5Frontend.deleteRows();
        boolean saves = rows == 3;
        String[] labels = saves ? new String[] { "Delete game", "Delete game and its saves", "Cancel" }
                                : new String[] { "Delete game", "Cancel" };
        Graphics2D g = smooth(img);
        g.setColor(new Color(6, 7, 14, 190)); g.fillRect(0, 0, W, H);
        int w = 860, rh = 64, gap = 10, h = 262 + rows * (rh + gap), x = (W - w) / 2, y = (H - h) / 2;
        g.setColor(new Color(14, 15, 28, 245)); g.fillRoundRect(x, y, w, h, 36, 36);
        glass(g, x, y, w, h, 36, alpha(in.accent, 26));
        icon(g, in, x + 48, y + 48, 96, 24);
        g.setFont(font(36, 2));
        text(g, fit(g, "Delete " + in.title + "?", w - 220), x + 172, y + 90, 36, 2, TEXT);
        g.setFont(font(21, 0));
        text(g, fit(g, f.getName() + "  ·  " + kb(in.bytes), w - 220), x + 172, y + 128, 21, 0, DIM);
        String note = saves ? "Saves and high scores are kept unless you choose to delete them too."
                            : "The game file is removed from the console. This cannot be undone.";
        g.setFont(font(20, 0));
        text(g, fit(g, note, w - 96), x + 48, y + 196, 20, 0, FAINT);
        int ly = y + 224;
        for (int i = 0; i < rows; i++) {
            int ry = ly + i * (rh + gap);
            boolean on = i == PS5Frontend.deleteAsk, danger = i < rows - 1;
            Color c = danger ? new Color(235, 87, 87) : in.accent;
            if (on) highlight(g, x + 40, ry, w - 80, rh, 20, c);
            else { g.setColor(new Color(255, 255, 255, 10)); g.fillRoundRect(x + 40, ry, w - 80, rh, 20, 20); }
            text(g, labels[i], x + 76, ry + rh / 2 + 9, 24, on ? 2 : 1, on ? TEXT : danger ? new Color(240, 150, 150) : alpha(TEXT, 215));
        }
        int hx = x + 40, hy = y + h + 24;
        hx = hint(g, "X", "Select", hx, hy);
        hint(g, "O", "Cancel", hx, hy);
        g.dispose();
    }

    /** The right-hand card: the game on a little phone, its name, and its settings. */
    static void hero(Graphics2D g, Info in, int x, int y, int w, int h) {
        Color accent = in.accent;
        /* the phone */
        final int px = x + 44, py = y + 40, pw = 200, ph = 348;
        g.drawImage(phonePiece(accent), px - 50, py - 50, null);
        int sx = px + 18, sy = py + 40, sw = pw - 36, sh = 176;
        if (in.icon != null) {
            int iw = in.icon.getWidth(), ih = in.icon.getHeight();
            double s = Math.min((sw - 40.0) / iw, (sh - 40.0) / ih);
            if (s >= 2) s = Math.floor(s);
            int dw = (int) (iw * s), dh = (int) (ih * s);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(in.icon, sx + (sw - dw) / 2, sy + (sh - dh) / 2, dw, dh, null);
        } else {
            String c = in.title.length() > 0 ? in.title.substring(0, 1).toUpperCase() : "?";
            int cw = width(g, c, 90, 2);
            text(g, c, sx + (sw - cw) / 2, sy + sh / 2 + 32, 90, 2, mix(accent, Color.WHITE, 0.6));
        }
        g.setColor(new Color(255, 255, 255, 18)); g.fillPolygon(new int[] { sx, sx + sw / 2, sx }, new int[] { sy, sy, sy + sh / 2 }, 3);
        details(g, in, x, y, w, px, pw);
    }

    static BufferedImage phonePiece(final Color accent) {
        return piece("phone" + accent.getRGB(), 300, 448, new Painter() {
            public void paint(Graphics2D p) { phone(p, 50, 50, 200, 348, accent); }
        });
    }

    /** Gets the next and previous games' pictures ready while the player looks at this one. */
    static void warm() {
        int n = PS5Frontend.games.size();
        if (n == 0) return;
        BufferedImage dummy = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dummy.createGraphics();
        for (int d : new int[] { 1, -1 }) {
            Info in = info(PS5Frontend.games.get((PS5Frontend.selected + d + n) % n));
            backdrop(in.accent);
            phonePiece(in.accent);
            highlight(g, 0, 0, 980, 100, 26, in.accent);
        }
        g.dispose();
    }

    /** The phone itself, without what is on its screen. */
    static void phone(Graphics2D g, int px, int py, int pw, int ph, Color accent) {
        for (int i = 6; i >= 1; i--) { /* soft glow behind the phone */
            g.setColor(alpha(accent, 7));
            g.fillRoundRect(px - i * 7, py - i * 7, pw + i * 14, ph + i * 14, 40 + i * 14, 40 + i * 14);
        }
        g.setColor(new Color(0x12141f)); g.fillRoundRect(px, py, pw, ph, 40, 40);
        g.setStroke(new BasicStroke(2.5f)); g.setColor(new Color(255, 255, 255, 46)); g.drawRoundRect(px, py, pw, ph, 40, 40);
        g.setColor(new Color(255, 255, 255, 40)); g.fillRoundRect(px + pw / 2 - 26, py + 18, 52, 6, 6, 6);
        int sx = px + 18, sy = py + 40, sw = pw - 36, sh = 176;
        g.setPaint(new GradientPaint(sx, sy, mix(accent, Color.BLACK, 0.45), sx, sy + sh, mix(accent, Color.BLACK, 0.8)));
        g.fillRoundRect(sx, sy, sw, sh, 10, 10);
        /* keypad: soft keys, a d-pad and number keys */
        int ky = sy + sh + 18;
        g.setColor(new Color(255, 255, 255, 34));
        g.fillRoundRect(sx, ky, 44, 14, 8, 8); g.fillRoundRect(sx + sw - 44, ky, 44, 14, 8, 8);
        g.setColor(new Color(255, 255, 255, 24)); g.fillOval(px + pw / 2 - 24, ky - 4, 48, 48);
        g.setColor(alpha(accent, 170)); g.fillOval(px + pw / 2 - 9, ky + 11, 18, 18);
        g.setColor(new Color(255, 255, 255, 30));
        for (int r = 0; r < 2; r++) for (int c = 0; c < 3; c++) g.fillRoundRect(sx + 8 + c * 52, ky + 56 + r * 24, 36, 14, 8, 8);
    }

    static void details(Graphics2D g, Info in, int x, int y, int w, int px, int pw) {
        Color accent = in.accent;
        /* name and details */
        int tx = px + pw + 52, tw = x + w - 44 - tx;
        g.setFont(font(40, 2));
        String[] lines = wrap(g, in.title, tw, 2);
        int ty = y + 96;
        for (String l : lines) { text(g, l, tx, ty, 40, 2, TEXT); ty += 50; }
        String sub = in.vendor + (in.vendor.length() > 0 && in.version.length() > 0 ? "  ·  " : "") + (in.version.length() > 0 ? "v" + in.version : "");
        g.setFont(font(22, 0));
        if (sub.length() > 0) { text(g, fit(g, sub, tw), tx, ty, 22, 0, DIM); ty += 34; }
        text(g, kb(in.bytes) + "  ·  " + PS5Frontend.games.get(PS5Frontend.selected).getName(), tx, ty, 18, 0, FAINT);
        int cx = tx, cy = ty + 30;
        int[] cs = PS5Frontend.currentSize();
        cx = chip(g, cs[0] + " × " + cs[1], cx, cy, true, accent);
        if (PS5Frontend.fps60) cx = chip(g, "60 FPS", cx, cy, true, accent);
        if (PS5Frontend.speed3d > 0) cx = chip(g, "Fast 3D", cx, cy, true, accent);
        if (PS5Frontend.rotation > 0) cx = chip(g, "Rotated", cx, cy, true, accent);
        if (PS5Touch.enabled && cx < x + w - 160) chip(g, "Touch", cx, cy, true, accent);

        /* settings */
        int rx = x + 32, rw = w - 64, ry = y + 420, step = 55;
        g.setColor(EDGE_SOFT); g.fillRect(rx, ry - 26, rw, 1);
        String size = cs[0] + " × " + cs[1];
        if (PS5Frontend.sizeIndex < 0) size = (PS5Frontend.detected != null ? "Auto  " : "Auto (guess)  ") + size;
        if (cs[0] > 1024 || cs[1] > 1024) size += "  · too large for 3D";
        String pic = PS5Frontend.SCALE_NAMES[PS5Frontend.scaleMode] + ", " + (PS5Frontend.filter == 0 ? "no upscale" : PS5Frontend.FILTER_NAMES[PS5Frontend.filter]);
        setting(g, "Phone screen", size, "<>", rx, ry, rw);
        setting(g, "Picture", pic, "S", rx, ry + step, rw);
        setting(g, "60 FPS", PS5Frontend.fps60 ? "On" : "Off", "L2", rx, ry + 2 * step, rw);
        setting(g, "Faster 3D", PS5Frontend.SPEED3D_NAMES[PS5Frontend.speed3d], "R3", rx, ry + 3 * step, rw);
        setting(g, "Touch screen", PS5Touch.enabled ? "On  (right stick + R2)" : "Off", "L1", rx, ry + 4 * step, rw);
        setting(g, "Frame counter", PS5Frontend.showFps ? "On" : "Off", "L3", rx, ry + 5 * step, rw);
        setting(g, "Emulator", PS5Frontend.engineText(), "R2", rx, ry + 6 * step, rw);
    }

    static String[] wrap(Graphics2D g, String s, int width, int max) {
        FontMetrics m = g.getFontMetrics();
        if (m.stringWidth(s) <= width) return new String[] { s };
        int cut = s.length();
        while (cut > 0 && m.stringWidth(s.substring(0, cut)) > width) {
            int sp = s.lastIndexOf(' ', cut - 1);
            cut = sp > 0 ? sp : cut - 1;
        }
        if (cut <= 0) return new String[] { fit(g, s, width) };
        String rest = s.substring(cut).trim();
        return max <= 1 ? new String[] { fit(g, s, width) } : new String[] { s.substring(0, cut).trim(), fit(g, rest, width) };
    }

    static void setting(Graphics2D g, String label, String value, String button, int x, int y, int w) {
        text(g, label, x + 12, y + 30, 22, 0, DIM);
        int bw = button.length() == 1 ? 40 : button.equals("<>") ? 62 : width(g, button, 18, 2) + 26;
        int vx = x + 250;
        g.setFont(font(24, 1));
        text(g, fit(g, value, w - 250 - bw - 30), vx, y + 30, 24, 1, TEXT);
        int gx = x + w - bw - 8;
        glyph(g, button, gx, y, 40);
    }

    static void picture(BufferedImage img) {
        Color accent = selectedAccent();
        Graphics2D g = page(img, accent, "Picture", "Rotation is per game · the rest apply to all games");
        listRows(g, accent, PS5Frontend.PICTURE_ROWS, PS5Frontend.pictureRow, 150, 70, true);
        int hx = 72, hy = H - 80;
        hx = hint(g, "UP / DOWN", "Choose", hx, hy);
        hx = hint(g, "LEFT / RIGHT", "Change", hx, hy);
        hx = hint(g, "S", "Defaults", hx, hy);
        hx = hint(g, "O", "Save and back", hx, hy);
        g.dispose();
    }

    /** Label on the left, value on the right; used by the picture screen. */
    static void listRows(Graphics2D g, Color accent, int n, int sel, int top, int rh, boolean picture) {
        int x = 72, w = W - 144, gap = 10;
        glass(g, x - 16, top - 16, w + 32, n * (rh + gap) - gap + 32, 40, new Color(255, 255, 255, 8));
        for (int i = 0; i < n; i++) {
            int y = top + i * (rh + gap);
            boolean on = i == sel;
            if (on) highlight(g, x, y, w, rh, 24, accent);
            String label = PS5Frontend.pictureLabel(i), v = PS5Frontend.pictureValue(i);
            text(g, label, x + 40, y + rh / 2 + 10, 28, on ? 2 : 0, on ? TEXT : alpha(TEXT, 210));
            valuePill(g, v, x + w - 24, y + (rh - 54) / 2, 54, on, accent, 26);
        }
    }

    /** The current value of a setting, right-aligned at xr, with arrows when it is selected. */
    static void valuePill(Graphics2D g, String v, int xr, int y, int h, boolean on, Color accent, int size) {
        int vw = width(g, v, size, 1) + (on ? 92 : 48), vx = xr - vw;
        g.setColor(on ? new Color(0, 0, 0, 70) : GLASS); g.fillRoundRect(vx, y, vw, h, h, h);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(on ? alpha(Color.WHITE, 60) : EDGE_SOFT); g.drawRoundRect(vx, y, vw, h, h, h);
        int tx = vx + (on ? 46 : 24);
        text(g, v, tx, y + h / 2 + size * 7 / 20, size, 1, on ? TEXT : mix(accent, Color.WHITE, 0.55));
        if (on) {
            g.setColor(alpha(Color.WHITE, 200));
            int my = y + h / 2;
            g.fillPolygon(new int[] { vx + 18, vx + 30, vx + 30 }, new int[] { my, my - 8, my + 8 }, 3);
            g.fillPolygon(new int[] { vx + vw - 18, vx + vw - 30, vx + vw - 30 }, new int[] { my, my - 8, my + 8 }, 3);
        }
    }

    static void controls(BufferedImage img) {
        Color accent = selectedAccent();
        Graphics2D g = page(img, accent, "Controls", "Which phone key each button presses");
        int n = PS5Frontend.INPUTS.length, perCol = (n + 1) / 2, cw = 856, rh = 70, gap = 8, ly = 168;
        for (int col = 0; col < 2; col++)
            glass(g, 72 + col * (cw + 64) - 16, ly - 16, cw + 32, perCol * (rh + gap) - gap + 32, 36, new Color(255, 255, 255, 8));
        for (int i = 0; i < n; i++) {
            int x = 72 + (i / perCol) * (cw + 64), y = ly + (i % perCol) * (rh + gap);
            boolean on = i == PS5Frontend.controlRow;
            if (on) highlight(g, x, y, cw, rh, 20, accent);
            String in = PS5Frontend.INPUT_NAMES[i];
            String sym = in.equals("Cross") ? "X" : in.equals("Circle") ? "O" : in.equals("Square") ? "S" : in.equals("Triangle") ? "T" : null;
            int tx = x + 28;
            if (sym != null) { glyph(g, sym, tx, y + 15, 40); tx += 54; }
            text(g, in, tx, y + 45, 25, on ? 2 : 0, on ? TEXT : alpha(TEXT, 210));
            String v = PS5Frontend.KEY_NAMES[PS5Frontend.map[i]];
            if (PS5Frontend.map[i] == 0) {
                if (on) valuePill(g, v, x + cw - 16, y + 12, 46, true, accent, 22);
                else text(g, v, x + cw - 24 - width(g, v, 22, 0), y + 44, 22, 0, FAINT);
            } else valuePill(g, v, x + cw - 16, y + 12, 46, on, accent, 22);
        }
        int hx = 72, hy = H - 80;
        hx = hint(g, "UP / DOWN", "Choose", hx, hy);
        hx = hint(g, "LEFT / RIGHT", "Change key", hx, hy);
        hx = hint(g, "S", "Defaults", hx, hy);
        hx = hint(g, "O", "Save and back", hx, hy);
        g.dispose();
    }

    /** The in-game menu: a dark glass panel on the left, the game still visible beside it. */
    static BufferedImage quick() {
        int pw = 780;
        Color accent = selectedAccent();
        BufferedImage img = new BufferedImage(pw, H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        g.setPaint(new GradientPaint(0, 0, new Color(10, 11, 22, 242), pw, 0, new Color(10, 11, 22, 220)));
        g.fillRect(0, 0, pw, H);
        g.setPaint(new GradientPaint(0, 0, alpha(accent, 70), 0, 260, alpha(accent, 0)));
        g.fillRect(0, 0, pw, 260);
        g.setColor(EDGE_SOFT); g.fillRect(pw - 2, 0, 2, H);
        text(g, "Quick menu", 56, 96, 42, 2, TEXT);
        if (!PS5Frontend.games.isEmpty()) {
            g.setFont(font(22, 0));
            text(g, fit(g, info(PS5Frontend.games.get(PS5Frontend.selected)).title, pw - 120), 56, 134, 22, 0, DIM);
        }
        int x = 40, cw = pw - 80, rh = 48, gap = 4, ly = 150;
        for (int i = 0; i < PS5Frontend.QUICK_ROWS; i++) {
            int y = ly + i * (rh + gap);
            boolean on = i == PS5Frontend.quickRow;
            if (i == 3 || i == PS5Frontend.QUICK_ROWS - 2) { g.setColor(EDGE_SOFT); g.fillRect(x + 16, y - gap / 2 - 1, cw - 32, 1); }
            if (on) highlight(g, x, y, cw, rh, 20, accent);
            String label = PS5Frontend.quickLabel(i), v = PS5Frontend.quickValue(i);
            g.setFont(font(22, on ? 2 : 0));
            text(g, fit(g, label, v == null ? cw - 60 : cw - 330), x + 30, y + rh / 2 + 8, 22, on ? 2 : 0, on ? TEXT : alpha(TEXT, 215));
            if (v != null) {
                g.setFont(font(19, 1));
                valuePill(g, fit(g, v, on ? 200 : 240), x + cw - 12, y + 6, rh - 12, on, accent, 19);
            }
        }
        int hy = ly + PS5Frontend.QUICK_ROWS * (rh + gap) + 18;
        if (PS5Frontend.quickNote != null) {
            g.setFont(font(22, 1));
            String[] l = wrap(g, PS5Frontend.quickNote, cw - 40, 2);
            glass(g, x, hy, cw, 28 + l.length * 34, 22, alpha(accent, 40));
            for (int i = 0; i < l.length; i++) text(g, l[i], x + 20, hy + 42 + i * 34, 22, 1, TEXT);
        } else {
            int hx = hint(g, "O", "Resume", x, hy);
            hint(g, "LEFT / RIGHT", "Change", hx, hy);
            text(g, "L3 + R3 at any time: back to the library", x, hy + 92, 20, 0, FAINT);
        }
        g.dispose();
        return img;
    }

    /** Shown the moment a game is chosen, until its first picture arrives. */
    static BufferedImage loading(String l) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Info in = info(PS5Frontend.games.get(PS5Frontend.selected));
        Graphics2D g = smooth(img);
        g.drawImage(backdrop(in.accent), 0, 0, null);
        int px = W / 2 - 100, py = 250;
        g.drawImage(phonePiece(in.accent), px - 50, py - 50, null);
        int sx = px + 18, sy = py + 40, sw = 164, sh = 176;
        if (in.icon != null) {
            int iw = in.icon.getWidth(), ih = in.icon.getHeight();
            double s = Math.min((sw - 40.0) / iw, (sh - 40.0) / ih);
            if (s >= 2) s = Math.floor(s);
            int dw = (int) (iw * s), dh = (int) (ih * s);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(in.icon, sx + (sw - dw) / 2, sy + (sh - dh) / 2, dw, dh, null);
        } else {
            String c = in.title.length() > 0 ? in.title.substring(0, 1).toUpperCase() : "?";
            text(g, c, sx + (sw - width(g, c, 90, 2)) / 2, sy + sh / 2 + 32, 90, 2, mix(in.accent, Color.WHITE, 0.6));
        }
        g.setFont(font(48, 2));
        String t = fit(g, in.title, W - 300);
        text(g, t, (W - width(g, t, 48, 2)) / 2, 720, 48, 2, TEXT);
        text(g, l, (W - width(g, l, 26, 0)) / 2, 772, 26, 0, DIM);
        /* a short bar in the game's colour */
        g.setColor(new Color(255, 255, 255, 24)); g.fillRoundRect(W / 2 - 160, 812, 320, 6, 6, 6);
        g.setColor(mix(in.accent, Color.WHITE, 0.25)); g.fillRoundRect(W / 2 - 160, 812, 120, 6, 6, 6);
        g.dispose();
        return img;
    }

    /* ---- the phone frame drawn around the game while playing ---- */

    /* ---- arcade bezels: artwork beside the game (res/bezels, made by make_bezels.py) ---- */

    /** Where the game goes inside a bezel: as big as fits, leaving a thin frame. */
    static int[] bezelWindow(int w, int h) {
        double s = Math.min(1840.0 / w, 1016.0 / h);
        int ww = (int) (w * s), wh = (int) (h * s);
        return new int[] { W / 2 - ww / 2, H / 2 - wh / 2, ww, wh };
    }

    static String bezelLoaded;
    static BufferedImage bezelArt;

    /** The artwork with a dark screen surround drawn round the game's window. */
    static synchronized BufferedImage bezel(String file, int[] win) {
        try {
            if (!file.equals(bezelLoaded)) {
                bezelArt = null;
                java.io.InputStream in = Ui.class.getResourceAsStream("/bezels/" + file);
                if (in == null) return null;
                try { bezelArt = ImageIO.read(in); } finally { in.close(); }
                bezelLoaded = file;
            }
        } catch (Exception e) { System.out.println("j2me: bezel " + file + ": " + e); return null; }
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smooth(img);
        g.drawImage(bezelArt, 0, 0, W, H, null);
        int x = win[0], y = win[1], w = win[2], h = win[3];
        for (int i = 8; i >= 1; i--) { /* soft shadow round the screen */
            g.setColor(new Color(0, 0, 0, 22));
            g.fillRoundRect(x - 12 - i * 3, y - 12 - i * 3, w + 24 + i * 6, h + 24 + i * 6, 24 + i * 6, 24 + i * 6);
        }
        g.setColor(new Color(6, 6, 10)); g.fillRoundRect(x - 12, y - 12, w + 24, h + 24, 16, 16);
        g.setStroke(new BasicStroke(2f)); g.setColor(new Color(255, 255, 255, 70)); g.drawRoundRect(x - 12, y - 12, w + 24, h + 24, 16, 16);
        g.dispose();
        return img;
    }

    /** Emulator key codes (as in PS5Frontend.KEY_CODES) for the keys on the frame. */
    static final int K_UP = 0, K_DOWN = 1, K_LEFT = 2, K_RIGHT = 3, K_FIRE = 7, K_LSOFT = 9, K_RSOFT = 8;
    static final int[] K_NUM = { 6, 10, 14, 11, 15, 18, 16, 5, 17, 4 }; /* 0..9 */
    static final int K_STAR = 12, K_POUND = 13;

    /** Where the game's screen goes on the phone, for a game picture w x h (already turned). */
    static int[] frameWindow(int w, int h) {
        double s = Math.min(840.0 / w, 880.0 / h);
        int ww = (int) (w * s), wh = (int) (h * s);
        return new int[] { W / 2 - ww / 2, 96 + (880 - wh) / 2, ww, wh };
    }

    /** A look for the phone: its plastic, its keys and the colour keys light up in. */
    static class Skin {
        String name, brand;
        Color bodyTop, bodyBot, edgeTop, edgeBot, keyTop, keyBot, keyText, keySub, keyEdge, lit, litText, arrow, bezel, brandColor;
        int keyRound, gloss; /* corner size (99 = pill), shine on the upper half */
        boolean tintBody; /* body takes the game's colour */
    }
    static final Skin[] SKINS = new Skin[5];
    static {
        Skin k;
        k = SKINS[0] = new Skin(); k.name = "Midnight"; k.brand = "J2ME";
        k.bodyTop = null; k.bodyBot = new Color(0x0b0c12); k.edgeTop = new Color(0xc9ccd8); k.edgeBot = new Color(0x4a4d5a);
        k.keyTop = new Color(0x3a3e4d); k.keyBot = new Color(0x1c1e27); k.keyText = new Color(0xe9ebf3); k.keySub = new Color(0x9aa0b8);
        k.keyEdge = new Color(255, 255, 255, 46); k.lit = null; k.litText = new Color(0x10111a); k.arrow = new Color(0xc9ccd8);
        k.bezel = new Color(0x040508); k.brandColor = new Color(0xb8bccb); k.keyRound = 34; k.gloss = 34;
        k = SKINS[1] = new Skin(); k.name = "Silver classic"; k.brand = "J2ME";
        k.bodyTop = new Color(0xe3e6ec); k.bodyBot = new Color(0x8c919d); k.edgeTop = new Color(0xffffff); k.edgeBot = new Color(0x5d616c);
        k.keyTop = new Color(0xf7f8fa); k.keyBot = new Color(0xc3c8d2); k.keyText = new Color(0x1d2030); k.keySub = new Color(0x5b6070);
        k.keyEdge = new Color(0, 0, 0, 50); k.lit = new Color(0x4ea3ff); k.litText = Color.WHITE; k.arrow = new Color(0x3a3e4c);
        k.bezel = new Color(0x0a0b0e); k.brandColor = new Color(0x3a3e4c); k.keyRound = 22; k.gloss = 60;
        k = SKINS[2] = new Skin(); k.name = "Walkman"; k.brand = "WALKMAN";
        k.bodyTop = new Color(0x26262a); k.bodyBot = new Color(0x050506); k.edgeTop = new Color(0x55555a); k.edgeBot = new Color(0x18181a);
        k.keyTop = new Color(0x2c2c30); k.keyBot = new Color(0x111113); k.keyText = new Color(0xff7a1a); k.keySub = new Color(0xb8622a);
        k.keyEdge = new Color(255, 122, 26, 70); k.lit = new Color(0xff7a1a); k.litText = new Color(0x111113); k.arrow = new Color(0xff7a1a);
        k.bezel = new Color(0x000000); k.brandColor = new Color(0xff7a1a); k.keyRound = 16; k.gloss = 22;
        k = SKINS[3] = new Skin(); k.name = "Brick"; k.brand = "J2ME";
        k.bodyTop = new Color(0x40506b); k.bodyBot = new Color(0x1c2434); k.edgeTop = new Color(0x7d8cab); k.edgeBot = new Color(0x2a3346);
        k.keyTop = new Color(0xc4ccdb); k.keyBot = new Color(0x8b96ab); k.keyText = new Color(0x1d2433); k.keySub = new Color(0x3c4658);
        k.keyEdge = new Color(0, 0, 0, 70); k.lit = new Color(0x9cff6a); k.litText = new Color(0x15301a); k.arrow = new Color(0x1d2433);
        k.bezel = new Color(0x1b2a1d); k.brandColor = new Color(0xc4ccdb); k.keyRound = 99; k.gloss = 26;
        k = SKINS[4] = new Skin(); k.name = "Candy"; k.brand = "J2ME";
        k.tintBody = true; k.edgeTop = Color.WHITE; k.edgeBot = new Color(255, 255, 255, 120);
        k.keyTop = Color.WHITE; k.keyBot = new Color(0xe3e6ee); k.keyText = new Color(0x1d2030); k.keySub = new Color(0x6b7080);
        k.keyEdge = new Color(0, 0, 0, 40); k.lit = null; k.litText = Color.WHITE; k.arrow = new Color(0x1d2030);
        k.bezel = new Color(0x08090d); k.brandColor = Color.WHITE; k.keyRound = 40; k.gloss = 70;
    }
    static Skin skin = SKINS[0];
    static Color litOf(Color accent) { return skin.lit != null ? skin.lit : skin.tintBody ? mix(accent, Color.BLACK, 0.35) : accent; }

    /** The whole phone at 1920x1080, with every key lit or none; adds each key's place to keys. */
    static BufferedImage phoneFrame(Color accent, int[] win, boolean lit, java.util.ArrayList<int[]> keys) {
        return phoneFrame(accent, win, lit, keys, PS5Frontend.frameSkin);
    }

    static synchronized BufferedImage phoneFrame(Color accent, int[] win, boolean lit, java.util.ArrayList<int[]> keys, int skinIndex) {
        skin = SKINS[Math.max(0, Math.min(SKINS.length - 1, skinIndex))];
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = smooth(img);
        g.drawImage(backdrop(accent), 0, 0, null);
        int bx = 150, by = 26, bw = W - 300, bh = H - 52, r = 120;
        /* shadow and glow under the phone */
        for (int i = 5; i >= 1; i--) { g.setColor(alpha(accent, 6)); g.fillRoundRect(bx - i * 9, by - i * 6, bw + i * 18, bh + i * 12, r + i * 18, r + i * 18); }
        /* body: the skin's plastic, a glossy top and an edge */
        Color top = skin.tintBody ? mix(accent, Color.WHITE, 0.15) : skin.bodyTop != null ? skin.bodyTop : mix(mix(accent, new Color(0x2a2d3a), 0.82), Color.WHITE, 0.04);
        Color bottom = skin.tintBody ? mix(accent, Color.BLACK, 0.45) : skin.bodyBot;
        g.setPaint(new GradientPaint(0, by, top, 0, by + bh, bottom)); g.fillRoundRect(bx, by, bw, bh, r, r);
        g.setPaint(new GradientPaint(0, by, new Color(255, 255, 255, skin.gloss), 0, by + bh * 0.45f, new Color(255, 255, 255, 0)));
        g.fillRoundRect(bx + 6, by + 6, bw - 12, (int) (bh * 0.45), r - 10, r - 10);
        g.setStroke(new BasicStroke(4f)); g.setPaint(new GradientPaint(0, by, skin.edgeTop, 0, by + bh, skin.edgeBot));
        g.drawRoundRect(bx, by, bw, bh, r, r);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(new Color(255, 255, 255, 30)); g.drawRoundRect(bx + 16, by + 16, bw - 32, bh - 32, r - 26, r - 26);

        /* screen: black glass around the game, earpiece above, the name below */
        int wx = win[0], wy = win[1], ww = win[2], wh = win[3], m = 22;
        g.setColor(skin.bezel); g.fillRoundRect(wx - m, wy - m, ww + 2 * m, wh + 2 * m, 30, 30);
        g.setStroke(new BasicStroke(2f)); g.setColor(new Color(255, 255, 255, 40)); g.drawRoundRect(wx - m, wy - m, ww + 2 * m, wh + 2 * m, 30, 30);
        g.setColor(new Color(0x0d0f16)); g.fillRoundRect(W / 2 - 90, by + 30, 180, 14, 14, 14);
        g.setColor(new Color(255, 255, 255, 20)); for (int i = 0; i < 9; i++) g.fillOval(W / 2 - 72 + i * 18, by + 33, 6, 6);
        String brand = skin.brand;
        text(g, brand, W / 2 - width(g, brand, 26, 2) / 2, Math.min(wy + wh + m + 44, by + bh - 18), 26, 2, skin.brandColor);

        /* left side: soft key, call key, the 5-way pad */
        int lx = bx + 50, lr = wx - m - 50, lw = lr - lx, lcx = (lx + lr) / 2;
        int mid = wy + wh / 2; /* both sides are centred on the screen */
        int kw = Math.min(170, lw / 2 - 14);
        int pr = Math.min(180, lw / 2 - 6), ltop = Math.max(by + 90, mid - (54 + 60 + 2 * pr) / 2);
        key(g, keys, lit, accent, K_LSOFT, lcx - kw - 8, ltop, kw, 54, "—", null, 0);
        callKey(g, lcx + 8, ltop, kw, 54, new Color(0x2fbf71));
        dpad(g, keys, lit, accent, lcx, ltop + 54 + 60 + pr, pr);

        /* right side: soft key, end key, number keys */
        int rx = wx + ww + m + 50, rr = bx + bw - 50, rw = rr - rx, rcx = (rx + rr) / 2;
        int kw2 = Math.min(170, rw / 2 - 14);
        int gap = 14, nk = Math.min(122, (rw - 2 * gap) / 3), nh = Math.min(100, (bh - 300) / 4 - gap);
        int rtop = Math.max(by + 90, mid - (54 + 46 + 4 * nh + 3 * gap) / 2);
        callKey(g, rcx - kw2 - 8, rtop, kw2, 54, new Color(0xe5484d));
        key(g, keys, lit, accent, K_RSOFT, rcx + 8, rtop, kw2, 54, "—", null, 0);
        int gx = rcx - (3 * nk + 2 * gap) / 2, gy = rtop + 54 + 46;
        String[] num = { "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#" };
        String[] sub = { "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz", "", "+", "" };
        int[] code = { K_NUM[1], K_NUM[2], K_NUM[3], K_NUM[4], K_NUM[5], K_NUM[6], K_NUM[7], K_NUM[8], K_NUM[9], K_STAR, K_NUM[0], K_POUND };
        for (int i = 0; i < 12; i++)
            key(g, keys, lit, accent, code[i], gx + (i % 3) * (nk + gap), gy + (i / 3) * (nh + gap), nk, nh, num[i], sub[i], 1);
        g.dispose();
        return img;
    }

    /** One key: a rounded cap, lit in the game's colour when pressed. */
    static void key(Graphics2D g, java.util.ArrayList<int[]> keys, boolean lit, Color accent, int code, int x, int y, int w, int h, String label, String sub, int style) {
        if (keys != null) keys.add(new int[] { code, x - 6, y - 6, w + 12, h + 12 });
        int rr = skin.keyRound >= 99 ? h : Math.min(h, skin.keyRound);
        Color lc = litOf(accent);
        if (lit) {
            g.setColor(alpha(lc, 90)); g.fillRoundRect(x - 6, y - 6, w + 12, h + 12, rr + 10, rr + 10);
            g.setPaint(new GradientPaint(0, y, mix(lc, Color.WHITE, 0.45), 0, y + h, lc));
        } else {
            g.setPaint(new GradientPaint(0, y, skin.keyTop, 0, y + h, skin.keyBot));
        }
        g.fillRoundRect(x, y, w, h, rr, rr);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(lit ? new Color(255, 255, 255, 120) : skin.keyEdge); g.drawRoundRect(x, y, w, h, rr, rr);
        Color tc = lit ? skin.litText : skin.keyText;
        if (style == 1) {
            int ls = Math.max(20, h * 2 / 5);
            int tw = width(g, label, ls, 2);
            boolean hasSub = sub != null && sub.length() > 0;
            text(g, label, x + (w - tw) / 2, y + (hasSub ? h / 2 + ls / 3 : h / 2 + ls * 7 / 20), ls, 2, tc);
            if (hasSub) text(g, sub, x + (w - width(g, sub, 15, 1)) / 2, y + h - 12, 15, 1, lit ? tc : skin.keySub);
        } else {
            g.setColor(tc); g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + w / 2 - 18, y + h / 2, x + w / 2 + 18, y + h / 2);
        }
    }

    /** Call and end keys are only decoration: J2ME games cannot use them. */
    static void callKey(Graphics2D g, int x, int y, int w, int h, Color c) {
        int rr = skin.keyRound >= 99 ? h : Math.min(h, skin.keyRound);
        g.setPaint(new GradientPaint(0, y, skin.keyTop, 0, y + h, skin.keyBot));
        g.fillRoundRect(x, y, w, h, rr, rr);
        g.setStroke(new BasicStroke(1.5f)); g.setColor(skin.keyEdge); g.drawRoundRect(x, y, w, h, rr, rr);
        /* a handset */
        int cx = x + w / 2, cy = y + h / 2;
        g.setColor(c); g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawArc(cx - 18, cy - 12, 36, 30, 20, 140);
    }

    /** The 5-way pad: four arrows round a centre OK key. */
    static void dpad(Graphics2D g, java.util.ArrayList<int[]> keys, boolean lit, Color accent, int cx, int cy, int r) {
        Color lc = litOf(accent);
        g.setPaint(new GradientPaint(0, cy - r, skin.keyTop, 0, cy + r, skin.keyBot));
        g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
        g.setStroke(new BasicStroke(2f)); g.setColor(skin.keyEdge); g.drawOval(cx - r, cy - r, 2 * r, 2 * r);
        int in = r * 2 / 5, a = r / 6;
        int[][] dirs = { { K_UP, 0, -1 }, { K_DOWN, 0, 1 }, { K_LEFT, -1, 0 }, { K_RIGHT, 1, 0 } };
        for (int[] d : dirs) {
            int px = cx + d[1] * (r * 2 / 3), py = cy + d[2] * (r * 2 / 3);
            int q = r / 4; /* kept clear of the OK key, so pressing one never lights the other */
            if (keys != null) keys.add(new int[] { d[0], px - q, py - q, 2 * q, 2 * q });
            if (lit) { g.setColor(alpha(lc, 170)); g.fillOval(px - q, py - q, 2 * q, 2 * q); }
            g.setColor(lit ? skin.litText : skin.arrow);
            int[] xs, ys;
            if (d[1] == 0) { xs = new int[] { px - a, px + a, px }; ys = new int[] { py - d[2] * a / 2, py - d[2] * a / 2, py + d[2] * a }; }
            else { xs = new int[] { px - d[1] * a / 2, px - d[1] * a / 2, px + d[1] * a }; ys = new int[] { py - a, py + a, py }; }
            g.fillPolygon(xs, ys, 3);
        }
        if (keys != null) keys.add(new int[] { K_FIRE, cx - in - 2, cy - in - 2, 2 * in + 4, 2 * in + 4 });
        if (lit) { g.setPaint(new GradientPaint(0, cy - in, mix(lc, Color.WHITE, 0.45), 0, cy + in, lc)); }
        else g.setPaint(new GradientPaint(0, cy - in, mix(skin.keyTop, Color.WHITE, 0.1), 0, cy + in, mix(skin.keyBot, Color.BLACK, 0.15)));
        g.fillOval(cx - in, cy - in, 2 * in, 2 * in);
        g.setColor(lit ? new Color(255, 255, 255, 140) : skin.keyEdge); g.setStroke(new BasicStroke(2.5f)); g.drawOval(cx - in, cy - in, 2 * in, 2 * in);
        String ok = "OK";
        text(g, ok, cx - width(g, ok, 24, 2) / 2, cy + 9, 24, 2, lit ? skin.litText : skin.keyText);
    }

    /** A one-line notice shown over the game. */
    static BufferedImage banner(String text) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D m = probe.createGraphics();
        int tw = width(m, text, 28, 1); m.dispose();
        Color accent = selectedAccent();
        BufferedImage img = new BufferedImage(Math.min(W - 64, tw + 120), 92, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = smooth(img);
        g.setColor(new Color(10, 11, 22, 236)); g.fillRoundRect(0, 0, img.getWidth() - 1, 91, 92, 92);
        g.setStroke(new BasicStroke(2f)); g.setColor(alpha(accent, 160)); g.drawRoundRect(1, 1, img.getWidth() - 3, 89, 90, 90);
        g.setColor(accent); g.fillOval(34, 38, 16, 16);
        g.setFont(font(28, 1));
        text(g, fit(g, text, img.getWidth() - 110), 70, 56, 28, 1, TEXT);
        g.dispose();
        return img;
    }
}

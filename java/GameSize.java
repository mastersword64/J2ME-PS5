import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Works out which phone screen a game was made for. J2ME has no standard
 * place for this, so it tries, in order: a screen-size line in the game's
 * description, a size in the file name, and full-screen pictures inside the
 * jar. Returns null when none of these says anything.
 */
public class GameSize {
    static final int[][] KNOWN = { {128, 128}, {128, 160}, {130, 130}, {132, 176}, {176, 208}, {176, 220},
        {208, 208}, {208, 320}, {240, 260}, {240, 297}, {240, 320}, {240, 400}, {240, 432}, {320, 240}, {320, 480},
        {352, 416}, {360, 640}, {400, 240}, {416, 352}, {480, 320}, {480, 360}, {480, 640}, {480, 800}, {480, 854},
        {540, 960}, {640, 360}, {640, 480}, {800, 480}, {854, 480}, {600, 1024}, {720, 1280} };
    static final Pattern TWO = Pattern.compile("(\\d{2,4})\\s*[xX,*]\\s*(\\d{2,4})");
    static final Pattern ATTR = Pattern.compile("(?i)^\\s*[\\w-]*(display-size|screensize|screen-size|canvas-size|lcd-size)[\\w-]*\\s*:\\s*(.+)$");

    static int[] ok(int w, int h) {
        return w >= 96 && w <= 1920 && h >= 96 && h <= 1920 ? new int[] { w, h } : null;
    }

    static int[] fromText(BufferedReader r) throws Exception {
        String line;
        while ((line = r.readLine()) != null) {
            Matcher a = ATTR.matcher(line);
            if (!a.matches()) continue;
            Matcher m = TWO.matcher(a.group(2));
            if (m.find()) { int[] s = ok(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))); if (s != null) return s; }
        }
        return null;
    }

    public static int[] detect(File f) {
        String name = f.getName().toLowerCase();
        try {
            if (name.endsWith(".jad")) {
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "ISO-8859-1"));
                try { int[] s = fromText(r); if (s != null) return s; } finally { r.close(); }
            }
            ZipFile z = name.endsWith(".jar") ? new ZipFile(f) : null;
            try {
                if (z != null) {
                    ZipEntry mf = z.getEntry("META-INF/MANIFEST.MF");
                    if (mf != null) {
                        int[] s = fromText(new BufferedReader(new InputStreamReader(z.getInputStream(mf), "ISO-8859-1")));
                        if (s != null) return s;
                    }
                }
                Matcher m = TWO.matcher(f.getName());
                while (m.find()) {
                    int[] s = ok(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                    if (s != null && Integer.parseInt(m.group(1)) >= 100) return s;
                }
                if (z != null) return fromPictures(z);
            } finally { if (z != null) z.close(); }
        } catch (Exception e) {
            System.out.println("j2me: size detection failed for " + f.getName() + ": " + e);
        }
        return null;
    }

    /** A picture exactly the size of a known phone screen is almost always a full-screen one. */
    static int[] fromPictures(ZipFile z) throws Exception {
        Map<Integer, Integer> votes = new HashMap<Integer, Integer>();
        int seen = 0;
        for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements() && seen < 4000; ) {
            ZipEntry en = e.nextElement();
            if (en.isDirectory() || en.getName().endsWith(".class") || en.getSize() < 100) continue;
            seen++;
            InputStream in = z.getInputStream(en);
            try {
                DataInputStream d = new DataInputStream(in);
                byte[] h = new byte[24];
                d.readFully(h);
                int w = 0, ht = 0;
                if ((h[0] & 255) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') {
                    w = ((h[16] & 255) << 24) | ((h[17] & 255) << 16) | ((h[18] & 255) << 8) | (h[19] & 255);
                    ht = ((h[20] & 255) << 24) | ((h[21] & 255) << 16) | ((h[22] & 255) << 8) | (h[23] & 255);
                } else if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F') {
                    w = (h[6] & 255) | ((h[7] & 255) << 8);
                    ht = (h[8] & 255) | ((h[9] & 255) << 8);
                } else continue;
                /* 3D games carry textures whose sides are powers of two (64, 128, 256...); those are not screens. */
                if (w > 0 && ht > 0 && (w & (w - 1)) == 0 && (ht & (ht - 1)) == 0) continue;
                if (w == ht) continue; /* square pictures are far more often sprites or textures than a screen */
                for (int i = 0; i < KNOWN.length; i++) {
                    if (KNOWN[i][0] == w && KNOWN[i][1] == ht) {
                        Integer v = votes.get(i);
                        votes.put(i, v == null ? 1 : v + 1);
                    }
                }
            } catch (Exception ex) {
            } finally { in.close(); }
        }
        int best = -1;
        for (Map.Entry<Integer, Integer> v : votes.entrySet()) {
            if (best < 0 || v.getValue() > votes.get(best) ||
                (v.getValue().equals(votes.get(best)) && KNOWN[v.getKey()][0] * KNOWN[v.getKey()][1] > KNOWN[best][0] * KNOWN[best][1]))
                best = v.getKey();
        }
        return best < 0 ? null : KNOWN[best];
    }

    /** Whether the game draws in 3D (M3G or Mascot Capsule): it carries .m3g/.mbac files or
     *  its classes use those APIs. */
    public static boolean uses3D(File f) {
        if (!f.getName().toLowerCase().endsWith(".jar")) return false;
        try {
            ZipFile z = new ZipFile(f);
            try {
                byte[] buf = new byte[65536];
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName().toLowerCase();
                    if (n.endsWith(".m3g") || n.endsWith(".mbac") || n.endsWith(".mtra")) return true;
                }
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    if (!en.getName().endsWith(".class")) continue;
                    InputStream in = z.getInputStream(en);
                    try {
                        int len = 0, r;
                        while (len < buf.length && (r = in.read(buf, len, buf.length - len)) > 0) len += r;
                        String text = new String(buf, 0, len, "ISO-8859-1");
                        if (text.indexOf("javax/microedition/m3g") >= 0 || text.indexOf("mascotcapsule") >= 0) return true;
                    } finally { in.close(); }
                }
            } finally { z.close(); }
        } catch (Exception e) { System.out.println("j2me: 3D check failed for " + f.getName() + ": " + e); }
        return false;
    }

    public static void main(String[] a) {
        for (String s : a) { int[] r = detect(new File(s)); System.out.println(s + " -> " + (r == null ? "not found" : r[0] + "x" + r[1])); }
    }
}

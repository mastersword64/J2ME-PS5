import java.util.HashMap;
import java.util.Map;
import javax.microedition.lcdui.Image;

/**
 * Watches how a game draws during its first seconds to find the phone screen it was
 * really made for (see PatchCanvas.java for where these are called from).
 *
 * Games written for one phone draw with fixed numbers: they clear the screen with
 * fillRect(0, 0, 240, 320), set the clip to the whole screen, draw a full-screen
 * background at 0,0, or keep an off-screen copy of the screen made with
 * Image.createImage(240, 320). When the screen the emulator was given is a different
 * size, those numbers still give the real one away.
 */
public class PS5Probe {
    public static volatile boolean active;
    static final HashMap<Long, int[]> votes = new HashMap<Long, int[]>();

    static boolean known(int w, int h) {
        for (int[] s : GameSize.KNOWN) if (s[0] == w && s[1] == h) return true;
        for (int[] s : PS5Frontend.SIZES) if (s[0] == w && s[1] == h) return true;
        return false;
    }

    static void vote(int w, int h, int n) {
        if (!known(w, h)) return;
        Long k = (long) w << 32 | h;
        synchronized (votes) {
            int[] v = votes.get(k);
            if (v == null) votes.put(k, v = new int[] { w, h, 0 });
            v[2] += n;
        }
    }

    /** fillRect and setClip */
    public static void rect(int x, int y, int w, int h) {
        if (active && x == 0 && y == 0 && w >= 96 && h >= 96) vote(w, h, 1);
    }

    /** drawImage */
    public static void image(Image img, int x, int y) {
        if (active && x == 0 && y == 0 && img != null) {
            try { int w = img.getWidth(), h = img.getHeight(); if (w >= 96 && h >= 96) vote(w, h, 1); } catch (Throwable t) {}
        }
    }

    /** Image.createImage(w, h): an off-screen copy of the whole screen is made once and is a strong hint. */
    public static void buffer(int w, int h) {
        if (active && w >= 96 && h >= 96) vote(w, h, 10);
    }

    static void reset() { synchronized (votes) { votes.clear(); } }

    /** The size the game seems to be made for, if it clearly differs from the screen it was given; else null. */
    static int[] verdict(int cw, int ch, boolean log) {
        int[] best = null;
        int canvas = 0;
        StringBuilder sb = new StringBuilder();
        synchronized (votes) {
            for (Map.Entry<Long, int[]> e : votes.entrySet()) {
                int[] v = e.getValue();
                sb.append(' ').append(v[0]).append('x').append(v[1]).append('=').append(v[2]);
                if (v[0] == cw && v[1] == ch) { canvas = v[2]; continue; }
                if (best == null || v[2] > best[2] || (v[2] == best[2] && v[0] * v[1] > best[0] * best[1])) best = v;
            }
        }
        if (log) System.out.println("j2me: screen size votes at " + cw + "x" + ch + ":" + (sb.length() == 0 ? " none" : sb.toString()));
        if (best == null || best[2] < 20 || best[2] * 2 < canvas) return null;
        return new int[] { best[0], best[1] };
    }
}

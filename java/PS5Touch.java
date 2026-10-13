/** Small pieces that the patched FreeJ2ME-Plus classes call into (see PatchCanvas.java). */
public class PS5Touch {
    /** Whether games are told the phone has a touch screen (a setting in the game list). */
    public static volatile boolean enabled;

    /** Arrays.fill that tolerates a buffer that has not been created yet. */
    public static void fillIfPresent(float[] a, float v) {
        if (a != null) java.util.Arrays.fill(a, v);
    }
}

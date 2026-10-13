import java.lang.reflect.Method;

/**
 * Starting a game used to spend most of its time getting the MIDI synthesizers
 * ready (four of them, each loading every instrument), and emulator v1.52 does
 * that again on every start. PatchCanvas renames Manager.prepareMediaEngine to
 * ps5PrepareMediaEngine0 and makes the original name call prepare() here, so the
 * work happens once, and the front end can do it in the background while the
 * player is still choosing a game.
 */
public class PS5Media {
    static boolean done;

    public static synchronized void prepare() {
        if (done) return;
        long t = System.currentTimeMillis();
        try {
            Method m = Class.forName("javax.microedition.media.Manager").getMethod("ps5PrepareMediaEngine0");
            m.invoke(null);
        } catch (Throwable e) { System.out.println("j2me: sound setup: " + e); }
        done = true;
        System.out.println("j2me: sound ready in " + (System.currentTimeMillis() - t) + " ms");
    }

    /** Gets sound ready on a background thread a moment after the menu appears. */
    public static void warmUp() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(1200); } catch (InterruptedException e) {}
                prepare();
            }
        }, "ps5-sound-warmup");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}

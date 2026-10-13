import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/**
 * Ends the running game inside this Java, so the player can go back to its game
 * library without the app being closed and started again: lets the game save
 * (destroyApp), silences its sounds, stops every thread it started, and resets
 * the emulator's state for the next game. Works with both emulator builds; the
 * parts of the emulator it touches are reached by name, so a missing one is skipped.
 */
public class PS5Unload {
    /** Threads the Java runtime and its sound system keep for themselves. */
    static final String[] KEEP = { "Reference Handler", "Finalizer", "Signal Dispatcher", "Common-Cleaner", "Notification Thread",
        "Attach Listener", "process reaper", "Java2D Disposer", "Java Sound Event Dispatcher", "Headspace mixer", "DestroyJavaVM" };

    static boolean keep(Thread t) {
        String n = t.getName();
        if (n.startsWith("ps5-") || n.equals("main")) return true;
        for (String k : KEEP) if (n.equals(k)) return true;
        return false;
    }

    static Object get(String cls, String field) {
        try {
            Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable e) { return null; }
    }

    static void set(String cls, String field, Object v) {
        try {
            Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            f.set(null, v);
        } catch (Throwable e) {}
    }

    static void call(Object o, String name) {
        if (o == null) return;
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                m.invoke(o);
                return;
            } catch (NoSuchMethodException e) {
            } catch (Throwable e) { return; }
        }
    }

    /** Lets the game save its progress, as a phone would when the game is closed. */
    static void destroyMidlet(long waitMs) {
        final Object midlet = get("org.recompile.mobile.Mobile", "midlet");
        if (midlet == null) return;
        Thread t = new Thread(new Runnable() { public void run() {
            try {
                Method m = Class.forName("javax.microedition.midlet.MIDlet").getDeclaredMethod("destroyApp", boolean.class);
                m.setAccessible(true);
                m.invoke(midlet, true);
            } catch (Throwable e) { /* the game may refuse; it is stopped anyway */ }
        } }, "ps5-destroy");
        t.setDaemon(true);
        t.start();
        try { t.join(waitMs); } catch (InterruptedException e) {}
    }

    /** Stops whatever the game was playing. The MIDI synthesizers stay open for the next game. */
    static void stopSound() {
        for (String cls : new String[] { "org.recompile.mobile.PlatformPlayer", "javax.microedition.media.Manager" }) {
            try {
                for (Field f : Class.forName(cls).getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    quiet(f.get(null), f.getName());
                }
            } catch (Throwable e) {}
        }
        try { /* sampled sounds still on the speakers */
            for (javax.sound.sampled.Mixer.Info mi : javax.sound.sampled.AudioSystem.getMixerInfo()) {
                for (javax.sound.sampled.Line l : javax.sound.sampled.AudioSystem.getMixer(mi).getSourceLines())
                    if (l instanceof javax.sound.sampled.Clip) { try { l.close(); } catch (Throwable e) {} }
            }
        } catch (Throwable e) {}
    }

    /** Before the emulator build is dropped: closes its MIDI synthesizers and sequencers too,
     *  so their sound output and threads end with it. */
    static void closeSound() {
        stopSound();
        closing = true;
        try {
            for (String cls : new String[] { "org.recompile.mobile.PlatformPlayer", "javax.microedition.media.Manager" }) {
                try {
                    for (Field f : Class.forName(cls).getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        quiet(f.get(null), f.getName());
                    }
                } catch (Throwable e) {}
            }
        } finally { closing = false; }
    }
    static boolean closing;

    static void quiet(Object v, String field) {
        if (v == null) return;
        if (v instanceof Collection) { for (Object o : new ArrayList<Object>((Collection<?>) v)) quiet(o, field); if (field.equals("runningPlayers")) ((Collection<?>) v).clear(); return; }
        if (v.getClass().isArray() && !v.getClass().getComponentType().isPrimitive()) {
            for (int i = 0; i < Array.getLength(v); i++) quiet(Array.get(v, i), field);
            if (field.equals("sequencePlayers")) for (int i = 0; i < Array.getLength(v); i++) try { Array.set(v, i, null); } catch (Throwable e) {}
            return;
        }
        if (closing && v instanceof javax.sound.midi.MidiDevice) { try { ((javax.sound.midi.MidiDevice) v).close(); } catch (Throwable e) {} return; }
        if (v instanceof javax.sound.midi.Sequencer) { try { ((javax.sound.midi.Sequencer) v).stop(); } catch (Throwable e) {} return; }
        if (v instanceof javax.sound.midi.Synthesizer) {
            try { for (javax.sound.midi.MidiChannel c : ((javax.sound.midi.Synthesizer) v).getChannels()) if (c != null) { c.allSoundOff(); c.resetAllControllers(); } } catch (Throwable e) {}
            return;
        }
        String n = v.getClass().getName();
        if (v instanceof javax.microedition.media.Player || n.contains("Player")) { call(v, "stop"); call(v, "close"); }
    }

    /** Stops every thread started since the game began. Returns how many were left running. */
    static int stopThreads(Set<Thread> before) {
        int left = 0;
        for (int round = 0; round < 20; round++) {
            ArrayList<Thread> game = new ArrayList<Thread>();
            for (Thread t : Thread.getAllStackTraces().keySet())
                if (t.isAlive() && t != Thread.currentThread() && !before.contains(t) && !keep(t)) game.add(t);
            left = game.size();
            if (left == 0) break;
            if (round == 0) System.out.println("j2me: stopping " + left + " game threads");
            try { PS5Frontend.stopThreads(game.toArray(new Thread[0]), new ThreadDeath()); }
            catch (UnsatisfiedLinkError e) { System.out.println("j2me: cannot stop threads here"); return left; }
            for (Thread t : game) t.interrupt();
            try { Thread.sleep(round < 5 ? 60 : 200); } catch (InterruptedException e) {}
        }
        if (left > 0) for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.isAlive() && !before.contains(t) && !keep(t) && t != Thread.currentThread()) System.out.println("j2me: still running: " + t.getName());
        return left;
    }

    /** Puts the emulator back the way a fresh start would have it. */
    static void reset() {
        set("org.recompile.mobile.MobilePlatform", "appTerminated", false);
        /* the next MobilePlatform only sets up its screen if the size looks new */
        set("org.recompile.mobile.MobilePlatform", "lcdWidth", 0);
        set("org.recompile.mobile.MobilePlatform", "lcdHeight", 0);
        set("org.recompile.mobile.MobilePlatform", "displayable", null);
        set("org.recompile.mobile.Mobile", "midlet", null);
        set("org.recompile.mobile.Mobile", "isPaused", false);
        set("org.recompile.mobile.MIDletLoader", "MIDletSelected", false);
        Object keys = get("org.recompile.mobile.MobilePlatform", "pressedKeys");
        if (keys instanceof boolean[]) java.util.Arrays.fill((boolean[]) keys, false);
    }
}

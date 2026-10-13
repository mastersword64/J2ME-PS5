import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;

/**
 * First Java class the app runs. Loads the front end together with one of the
 * two FreeJ2ME-Plus builds in a class loader of its own and runs it. When a game
 * needs the other build, the front end writes its choice to fj/engine and
 * returns; this class then stops everything the old build left running, drops
 * it, and starts the front end again on the other build, inside the same Java,
 * without closing the app.
 */
public class PS5Boot {
    /** Points the native side (controller, touch, drawing) at this front-end class; null while switching. */
    static native void bind(Class<?> frontend);
    /** Stops the given threads (JVMTI StopThread). Implemented in C. */
    static native void stopThreads(Thread[] threads, Throwable reason);

    /** Threads the Java runtime and its sound system keep for themselves. */
    static final String[] KEEP = { "Reference Handler", "Finalizer", "Signal Dispatcher", "Common-Cleaner", "Notification Thread",
        "Attach Listener", "process reaper", "Java2D Disposer", "Java Sound Event Dispatcher", "Headspace mixer", "DestroyJavaVM" };

    static String fj;
    /** Old builds that could not be freed; past this many, switching falls back to restarting the app. */
    static final int MAX_HELD = 2;
    static final ArrayList<java.lang.ref.WeakReference<ClassLoader>> dropped = new ArrayList<java.lang.ref.WeakReference<ClassLoader>>();

    static boolean wantsNewer() {
        if (!new File(fj, "freej2me-new.jar").canRead()) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(new File(fj, "engine")))) {
            String l = r.readLine();
            return l != null && l.startsWith("new");
        } catch (Exception e) { return false; }
    }

    public static void main(String[] args) throws Exception {
        fj = System.getProperty("ps5.fj", "/data/jvm/fj");
        String host = System.getProperty("ps5.hostboot"); /* bind/stopThreads for testing on a PC */
        if (host != null) System.load(host);
        System.setProperty("ps5.boot", "1");
        boolean switching = ClassLoader.getSystemClassLoader() instanceof PS5SysLoader;
        System.setProperty("ps5.switch", switching ? "1" : "0");
        if (!switching) System.out.println("j2me: system class loader is not PS5SysLoader; switching emulators will restart the app");
        /* AWT's main AppContext keeps the class loader of the thread that first needs it.
         * Create it now, under the system class loader, so it never holds an emulator build. */
        try {
            javax.imageio.spi.IIORegistry.getDefaultInstance();
            java.awt.Toolkit.getDefaultToolkit();
        } catch (Throwable e) { System.out.println("j2me: AWT setup: " + e); }
        for (int round = 0; ; round++) {
            long t0 = System.currentTimeMillis();
            boolean newer = wantsNewer();
            String eng = newer ? "new" : "classic";
            System.setProperty("ps5.engine", eng);
            URL[] urls = {
                new File(fj, "ps5frontend.jar").toURI().toURL(),
                new File(fj, "ps5patch-" + eng + ".jar").toURI().toURL(),
                new File(fj, newer ? "freej2me-new.jar" : "freej2me-lr.jar").toURI().toURL(),
            };
            URLClassLoader loader = new URLClassLoader("emulator-" + eng, urls, ClassLoader.getPlatformClassLoader());
            if (switching) PS5SysLoader.engine = loader;
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> fe = Class.forName("PS5Frontend", false, loader);
            bind(fe);
            System.out.println("j2me: emulator " + eng + (round > 0 ? " loaded in " + (System.currentTimeMillis() - t0) + " ms" : ""));
            try {
                fe.getMethod("main", String[].class).invoke(null, (Object) args);
            } catch (InvocationTargetException e) {
                System.out.println("j2me: the front end stopped with an exception");
                e.getCause().printStackTrace();
                return;
            }
            /* The front end returned: it wants the other emulator build. */
            long t1 = System.currentTimeMillis();
            bind(null);
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                public void uncaughtException(Thread t, Throwable e) { if (!(e instanceof ThreadDeath)) e.printStackTrace(); }
            });
            int left = stopAll(loader);
            if (switching) PS5SysLoader.engine = null;
            Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
            try { loader.close(); } catch (Exception e) {}
            java.lang.ref.WeakReference<ClassLoader> gone = new java.lang.ref.WeakReference<ClassLoader>(loader);
            fe = null;
            loader = null;
            for (int i = 0; i < 5 && gone.get() != null; i++) { System.gc(); try { Thread.sleep(30); } catch (InterruptedException e) {} }
            Runtime r = Runtime.getRuntime();
            System.out.println("j2me: old emulator dropped in " + (System.currentTimeMillis() - t1) + " ms, " + left
                + " threads left, memory used " + (r.totalMemory() - r.freeMemory()) / 1048576 + " MB"
                + (gone.get() == null ? ", unloaded" : ", still in memory"));
            dropped.add(gone);
            int held = 0;
            for (java.lang.ref.WeakReference<ClassLoader> w : dropped) if (w.get() != null) held++;
            if (held >= MAX_HELD) { /* something keeps old builds alive: stop piling them up */
                System.setProperty("ps5.switch", "0");
                System.out.println("j2me: " + held + " old emulator copies are still in memory; the next switch restarts the app instead");
            }
        }
    }

    static boolean keep(Thread t) {
        for (String k : KEEP) if (t.getName().equals(k)) return true;
        return false;
    }

    /** Stops every thread started by the old build (they inherited its class loader). */
    static int stopAll(ClassLoader old) {
        int left = 0;
        for (int round = 0; round < 20; round++) {
            ArrayList<Thread> list = new ArrayList<Thread>();
            for (Thread t : Thread.getAllStackTraces().keySet())
                if (t.isAlive() && t != Thread.currentThread() && t.getContextClassLoader() == old && !keep(t)) list.add(t);
            left = list.size();
            if (left == 0) break;
            if (round == 0) System.out.println("j2me: stopping " + left + " threads of the old emulator");
            try { stopThreads(list.toArray(new Thread[0]), new ThreadDeath()); }
            catch (UnsatisfiedLinkError e) { return left; }
            for (Thread t : list) t.interrupt();
            try { Thread.sleep(round < 5 ? 60 : 200); } catch (InterruptedException e) {}
        }
        /* Runtime threads that started while the old build ran inherited its class loader;
         * point them elsewhere so the old build can be freed. */
        for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.getContextClassLoader() == old && keep(t)) {
                try { t.setContextClassLoader(ClassLoader.getSystemClassLoader()); } catch (Throwable e) {}
                System.out.println("j2me: kept runtime thread " + t.getName());
            }
        if (left > 0) for (Thread t : Thread.getAllStackTraces().keySet())
            if (t.isAlive() && t != Thread.currentThread() && t.getContextClassLoader() == old && !keep(t))
                System.out.println("j2me: still running: " + t.getName());
        return left;
    }
}

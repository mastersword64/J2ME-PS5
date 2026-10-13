import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

/**
 * Java's system class loader for the app (-Djava.system.class.loader=PS5SysLoader).
 *
 * The emulator loads each game with a URLClassLoader whose parent is the system
 * class loader, so the game finds the phone APIs (javax.microedition...) there.
 * Those APIs live in whichever emulator build is running, which PS5Boot loads in
 * a class loader of its own so it can be swapped without restarting the app.
 * This loader passes every request on to that build first.
 */
public class PS5SysLoader extends ClassLoader {
    static { registerAsParallelCapable(); }

    /** The running emulator build's class loader, or null between builds. */
    static volatile ClassLoader engine;

    public PS5SysLoader(ClassLoader parent) { super(parent); }

    static boolean own(String name) { return name.startsWith("PS5Boot") || name.startsWith("PS5SysLoader"); }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        ClassLoader e = engine;
        if (e != null && !own(name)) {
            try { return e.loadClass(name); } catch (ClassNotFoundException x) { /* not part of the build */ }
        }
        return super.loadClass(name, resolve);
    }

    @Override
    public URL getResource(String name) {
        ClassLoader e = engine;
        URL u = e != null ? e.getResource(name) : null;
        return u != null ? u : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        ClassLoader e = engine;
        return e != null ? e.getResources(name) : super.getResources(name);
    }
}

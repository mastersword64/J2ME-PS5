import java.io.*;
import java.util.zip.*;
import org.objectweb.asm.*;

/** Build-time tool that makes two small changes to FreeJ2ME-Plus v1.52 classes. The changed
 *  classes go into ps5frontend.jar, which is ahead of the emulator's jar on the class path.
 *
 *  1. Canvas.hasPointerEvents() follows the player's "touch screen" setting, so games need
 *     not draw touch buttons on a console.
 *  2. Graphics3D.setDepthRange() no longer crashes when a game calls it before the first 3D
 *     frame: v1.52 clears a depth buffer there that does not exist yet. (Fixed upstream after
 *     v1.52 by removing the call; here it is redirected to a version that checks first.) */
public class PatchCanvas {
    static byte[] read(ZipFile z, String name) throws Exception {
        InputStream in = z.getInputStream(z.getEntry(name));
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[65536]; int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }

    static void write(String dir, String name, byte[] data) throws Exception {
        File out = new File(dir, name);
        out.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(out); o.write(data); o.close();
    }

    public static void main(String[] a) throws Exception {
        ZipFile z = new ZipFile(a[0]);
        final int[] hits = {0, 0};

        ClassWriter w = new ClassWriter(0);
        new ClassReader(read(z, "javax/microedition/lcdui/Canvas.class")).accept(new ClassAdapter(w) {
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                if (!name.equals("hasPointerEvents") || !desc.equals("()Z")) return mv;
                hits[0]++;
                mv.visitCode(); mv.visitFieldInsn(Opcodes.GETSTATIC, "PS5Touch", "enabled", "Z"); mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(1, 1); mv.visitEnd();
                return null; // drop the original body
            }
        }, 0);
        write(a[1], "javax/microedition/lcdui/Canvas.class", w.toByteArray());

        ClassWriter w2 = new ClassWriter(0);
        new ClassReader(read(z, "javax/microedition/m3g/Graphics3D.class")).accept(new ClassAdapter(w2) {
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                if (!name.equals("setDepthRange")) return mv;
                return new MethodAdapter(mv) {
                    public void visitMethodInsn(int op, String owner, String n, String d) {
                        if (op == Opcodes.INVOKESTATIC && owner.equals("java/util/Arrays") && n.equals("fill") && d.equals("([FF)V")) {
                            hits[1]++;
                            super.visitMethodInsn(op, "PS5Touch", "fillIfPresent", d);
                        } else super.visitMethodInsn(op, owner, n, d);
                    }
                };
            }
        }, 0);
        if (hits[1] > 0) write(a[1], "javax/microedition/m3g/Graphics3D.class", w2.toByteArray());
        /* 3. Screen-size detection: report full-screen drawing to PS5Probe. */
        final int[] probe = {0};
        ClassWriter w3 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(read(z, "org/recompile/mobile/PlatformGraphics.class")).accept(new ClassAdapter(w3) {
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                final boolean rect = (name.equals("fillRect") || name.equals("setClip")) && desc.equals("(IIII)V");
                final boolean img = name.equals("drawImage") && desc.equals("(Ljavax/microedition/lcdui/Image;III)V");
                if (!rect && !img) return mv;
                probe[0]++;
                return new MethodAdapter(mv) {
                    public void visitCode() {
                        super.visitCode();
                        if (rect) {
                            for (int i = 1; i <= 4; i++) super.visitVarInsn(Opcodes.ILOAD, i);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "PS5Probe", "rect", "(IIII)V");
                        } else {
                            super.visitVarInsn(Opcodes.ALOAD, 1); super.visitVarInsn(Opcodes.ILOAD, 2); super.visitVarInsn(Opcodes.ILOAD, 3);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "PS5Probe", "image", "(Ljavax/microedition/lcdui/Image;II)V");
                        }
                    }
                };
            }
        }, 0);
        write(a[1], "org/recompile/mobile/PlatformGraphics.class", w3.toByteArray());
        ClassWriter w4 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(read(z, "javax/microedition/lcdui/Image.class")).accept(new ClassAdapter(w4) {
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                if (!name.equals("createImage") || !(desc.equals("(II)Ljavax/microedition/lcdui/Image;") || desc.equals("(III)Ljavax/microedition/lcdui/Image;"))) return mv;
                probe[0]++;
                return new MethodAdapter(mv) {
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ILOAD, 0); super.visitVarInsn(Opcodes.ILOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "PS5Probe", "buffer", "(II)V");
                    }
                };
            }
        }, 0);
        write(a[1], "javax/microedition/lcdui/Image.class", w4.toByteArray());
        /* 4. Sound set up once (see PS5Media.java). */
        final int[] media = {0};
        ClassWriter w5 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        new ClassReader(read(z, "javax/microedition/media/Manager.class")).accept(new ClassAdapter(w5) {
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (name.equals("prepareMediaEngine") && desc.equals("()V")) { media[0]++; name = "ps5PrepareMediaEngine0"; }
                return super.visitMethod(acc, name, desc, sig, ex);
            }
            public void visitEnd() {
                MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "prepareMediaEngine", "()V", null, null);
                mv.visitCode();
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "PS5Media", "prepare", "()V");
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
                super.visitEnd();
            }
        }, 0);
        if (media[0] == 1) write(a[1], "javax/microedition/media/Manager.class", w5.toByteArray());
        System.out.println("patched: touch setting " + hits[0] + ", 3D depth range " + hits[1] + ", size probes " + probe[0] + ", sound setup " + media[0]);
    }
}

import java.nio.file.Files;
import java.nio.file.Path;
import org.jetbrains.org.objectweb.asm.ClassReader;
import org.jetbrains.org.objectweb.asm.ClassVisitor;
import org.jetbrains.org.objectweb.asm.ClassWriter;
import org.jetbrains.org.objectweb.asm.MethodVisitor;
import org.jetbrains.org.objectweb.asm.Opcodes;

/** Rewrites every sun.misc.Unsafe instance call and ARRAY_* constant read in one class file to ArtUnsafeShim. */
public final class RewriteUnsafeCalls {
    private static final String UNSAFE = "sun/misc/Unsafe";
    private static final String SHIM = "com/jetbrains/rd/framework/ArtUnsafeShim";

    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("usage: RewriteUnsafeCalls <input.class> <output.class>");
        }
        final var reader = new ClassReader(Files.readAllBytes(Path.of(arguments[0])));
        final var writer = new ClassWriter(0);
        final int[] rewritten = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    @Override
                    public void visitFieldInsn(final int opcode, final String owner, final String fieldName,
                                               final String fieldDescriptor) {
                        if (opcode == Opcodes.GETSTATIC && owner.equals(UNSAFE) && fieldName.startsWith("ARRAY_")) {
                            rewritten[0]++;
                            super.visitFieldInsn(opcode, SHIM, fieldName, fieldDescriptor);
                        } else {
                            super.visitFieldInsn(opcode, owner, fieldName, fieldDescriptor);
                        }
                    }

                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDescriptor, final boolean isInterface) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner.equals(UNSAFE)) {
                            rewritten[0]++;
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, SHIM, methodName,
                                "(L" + UNSAFE + ";" + methodDescriptor.substring(1), false);
                        } else {
                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                        }
                    }
                };
            }
        }, 0);
        Files.write(Path.of(arguments[1]), writer.toByteArray());
        System.out.println("Rewrote " + rewritten[0] + " Unsafe references in " + arguments[0]);
    }
}

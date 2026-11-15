import java.util.Arrays;

/** Prints which sun.misc.Unsafe methods used by rd's UnsafeBuffer exist on this runtime. */
public final class UnsafeProbe {
    public static void main(final String[] arguments) throws Exception {
        final var unsafeClass = Class.forName("sun.misc.Unsafe");
        final String[][] wanted = {
            {"allocateMemory", "long"}, {"freeMemory", "long"}, {"reallocateMemory", "long,long"},
            {"copyMemory", "java.lang.Object,long,java.lang.Object,long,long"},
            {"getByte", "java.lang.Object,long"}, {"putByte", "java.lang.Object,long,byte"},
            {"getShort", "java.lang.Object,long"}, {"putShort", "java.lang.Object,long,short"},
            {"getChar", "java.lang.Object,long"}, {"putChar", "java.lang.Object,long,char"},
            {"getInt", "java.lang.Object,long"}, {"putInt", "java.lang.Object,long,int"},
            {"getLong", "java.lang.Object,long"}, {"putLong", "java.lang.Object,long,long"},
            {"getFloat", "java.lang.Object,long"}, {"putFloat", "java.lang.Object,long,float"},
            {"getDouble", "java.lang.Object,long"}, {"putDouble", "java.lang.Object,long,double"},
        };
        for (final String[] method : wanted) {
            final boolean present = Arrays.stream(unsafeClass.getDeclaredMethods()).anyMatch(candidate ->
                candidate.getName().equals(method[0])
                    && Arrays.stream(candidate.getParameterTypes()).map(Class::getName).reduce((left, right) -> left + "," + right).orElse("").equals(method[1]));
            System.out.println((present ? "present  " : "MISSING  ") + method[0] + "(" + method[1] + ")");
        }
    }
}

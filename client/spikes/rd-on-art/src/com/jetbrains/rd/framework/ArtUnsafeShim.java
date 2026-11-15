package com.jetbrains.rd.framework;

import java.util.concurrent.ConcurrentHashMap;
import sun.misc.Unsafe;

/**
 * Stand-ins for the sun.misc.Unsafe calls in rd's UnsafeBuffer, so it runs on Android ART.
 * ART lacks reallocateMemory(long,long) and copyMemory(Object,long,Object,long,long), and its
 * (Object,long) accessors do not accept a null base for raw addresses, so every call is routed here.
 * A null base means "offset is a raw native address", as in HotSpot.
 */
public final class ArtUnsafeShim {
    // ponytail: byte-by-byte copies; switch to bulk copies if profiling shows buffer copies matter.
    private static final ConcurrentHashMap<Long, Long> allocationSizes = new ConcurrentHashMap<>();

    private static final Unsafe UNSAFE = loadUnsafe();

    // HotSpot's Unsafe exposes these as constants; ART does not, so they are computed from the running VM.
    public static final int ARRAY_BOOLEAN_BASE_OFFSET = UNSAFE.arrayBaseOffset(boolean[].class);
    public static final int ARRAY_BOOLEAN_INDEX_SCALE = UNSAFE.arrayIndexScale(boolean[].class);
    public static final int ARRAY_BYTE_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
    public static final int ARRAY_BYTE_INDEX_SCALE = UNSAFE.arrayIndexScale(byte[].class);
    public static final int ARRAY_CHAR_BASE_OFFSET = UNSAFE.arrayBaseOffset(char[].class);
    public static final int ARRAY_CHAR_INDEX_SCALE = UNSAFE.arrayIndexScale(char[].class);
    public static final int ARRAY_DOUBLE_BASE_OFFSET = UNSAFE.arrayBaseOffset(double[].class);
    public static final int ARRAY_DOUBLE_INDEX_SCALE = UNSAFE.arrayIndexScale(double[].class);
    public static final int ARRAY_FLOAT_BASE_OFFSET = UNSAFE.arrayBaseOffset(float[].class);
    public static final int ARRAY_FLOAT_INDEX_SCALE = UNSAFE.arrayIndexScale(float[].class);
    public static final int ARRAY_INT_BASE_OFFSET = UNSAFE.arrayBaseOffset(int[].class);
    public static final int ARRAY_INT_INDEX_SCALE = UNSAFE.arrayIndexScale(int[].class);
    public static final int ARRAY_LONG_BASE_OFFSET = UNSAFE.arrayBaseOffset(long[].class);
    public static final int ARRAY_LONG_INDEX_SCALE = UNSAFE.arrayIndexScale(long[].class);
    public static final int ARRAY_SHORT_BASE_OFFSET = UNSAFE.arrayBaseOffset(short[].class);
    public static final int ARRAY_SHORT_INDEX_SCALE = UNSAFE.arrayIndexScale(short[].class);

    private ArtUnsafeShim() {
    }

    private static Unsafe loadUnsafe() {
        try {
            final var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (final ReflectiveOperationException reflectionFailure) {
            throw new IllegalStateException("Cannot read sun.misc.Unsafe.theUnsafe", reflectionFailure);
        }
    }

    public static long allocateMemory(final Unsafe unsafe, final long bytes) {
        final long address = unsafe.allocateMemory(bytes);
        allocationSizes.put(address, bytes);
        return address;
    }

    public static void freeMemory(final Unsafe unsafe, final long address) {
        allocationSizes.remove(address);
        unsafe.freeMemory(address);
    }

    public static long reallocateMemory(final Unsafe unsafe, final long address, final long bytes) {
        final long fresh = allocateMemory(unsafe, bytes);
        if (address != 0) {
            final Long oldSize = allocationSizes.get(address);
            if (oldSize == null) {
                throw new IllegalStateException("reallocateMemory: unknown address " + address);
            }
            copyMemory(unsafe, null, address, null, fresh, Math.min(oldSize, bytes));
            freeMemory(unsafe, address);
        }
        return fresh;
    }

    public static void copyMemory(final Unsafe unsafe, final Object sourceBase, final long sourceOffset,
                                  final Object destinationBase, final long destinationOffset, final long bytes) {
        for (long index = 0; index < bytes; index++) {
            putByte(unsafe, destinationBase, destinationOffset + index, getByte(unsafe, sourceBase, sourceOffset + index));
        }
    }

    public static byte getByte(final Unsafe unsafe, final Object base, final long offset) {
        return base == null ? unsafe.getByte(offset) : unsafe.getByte(base, offset);
    }

    public static void putByte(final Unsafe unsafe, final Object base, final long offset, final byte value) {
        if (base == null) unsafe.putByte(offset, value); else unsafe.putByte(base, offset, value);
    }

    public static short getShort(final Unsafe unsafe, final Object base, final long offset) {
        return base == null ? unsafe.getShort(offset) : unsafe.getShort(base, offset);
    }

    public static void putShort(final Unsafe unsafe, final Object base, final long offset, final short value) {
        if (base == null) unsafe.putShort(offset, value); else unsafe.putShort(base, offset, value);
    }

    public static char getChar(final Unsafe unsafe, final Object base, final long offset) {
        return base == null ? unsafe.getChar(offset) : unsafe.getChar(base, offset);
    }

    public static void putChar(final Unsafe unsafe, final Object base, final long offset, final char value) {
        if (base == null) unsafe.putChar(offset, value); else unsafe.putChar(base, offset, value);
    }

    public static int getInt(final Unsafe unsafe, final Object base, final long offset) {
        return base == null ? unsafe.getInt(offset) : unsafe.getInt(base, offset);
    }

    public static void putInt(final Unsafe unsafe, final Object base, final long offset, final int value) {
        if (base == null) unsafe.putInt(offset, value); else unsafe.putInt(base, offset, value);
    }

    public static long getLong(final Unsafe unsafe, final Object base, final long offset) {
        return base == null ? unsafe.getLong(offset) : unsafe.getLong(base, offset);
    }

    public static void putLong(final Unsafe unsafe, final Object base, final long offset, final long value) {
        if (base == null) unsafe.putLong(offset, value); else unsafe.putLong(base, offset, value);
    }

    public static float getFloat(final Unsafe unsafe, final Object base, final long offset) {
        return base == null ? unsafe.getFloat(offset) : unsafe.getFloat(base, offset);
    }

    public static void putFloat(final Unsafe unsafe, final Object base, final long offset, final float value) {
        if (base == null) unsafe.putFloat(offset, value); else unsafe.putFloat(base, offset, value);
    }

    public static double getDouble(final Unsafe unsafe, final Object base, final long offset) {
        return base == null ? unsafe.getDouble(offset) : unsafe.getDouble(base, offset);
    }

    public static void putDouble(final Unsafe unsafe, final Object base, final long offset, final double value) {
        if (base == null) unsafe.putDouble(offset, value); else unsafe.putDouble(base, offset, value);
    }
}

package cn.net.rms.confluxmap.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** Default-preserving reads across the optional NBT API introduced in 1.21.5. */
public final class Nbts {
    private Nbts() {
    }

    public static boolean hasCompound(final CompoundTag compound, final String key) {
        return compound.getCompound(key).isPresent();
    }

    public static CompoundTag compound(final CompoundTag compound, final String key) {
        return compound.getCompound(key).orElseGet(CompoundTag::new);
    }

    public static String string(final CompoundTag compound, final String key) {
        return compound.getStringOr(key, "");
    }

    public static int integer(final CompoundTag compound, final String key) {
        return compound.getIntOr(key, 0);
    }

    public static int byteValue(final CompoundTag compound, final String key) {
        return compound.getByteOr(key, (byte) 0);
    }

    public static long longValue(final CompoundTag compound, final String key) {
        return compound.getLongOr(key, 0L);
    }

    public static long[] longArray(final CompoundTag compound, final String key) {
        return compound.getLongArray(key).orElseGet(() -> new long[0]);
    }

    public static int[] intArray(final CompoundTag compound, final String key) {
        return compound.getIntArray(key).orElseGet(() -> new int[0]);
    }

    public static byte[] byteArray(final CompoundTag compound, final String key) {
        return compound.getByteArray(key).orElseGet(() -> new byte[0]);
    }

    public static ListTag list(final CompoundTag compound, final String key, final int elementType) {
        return compound.getList(key).orElseGet(ListTag::new);
    }

    public static CompoundTag compound(final ListTag list, final int index) {
        return list.getCompound(index).orElseGet(CompoundTag::new);
    }

    public static String string(final ListTag list, final int index) {
        return list.getStringOr(index, "");
    }
}

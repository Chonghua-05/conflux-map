package cn.net.rms.confluxmap.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** Default-preserving reads across the optional NBT API introduced in 1.21.5. */
public final class Nbts {
    private Nbts() {
    }

    public static boolean hasCompound(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getCompound(key).isPresent();
        //#else
        //$$ return compound.contains(key, 10);
        //#endif
    }

    public static CompoundTag compound(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getCompound(key).orElseGet(CompoundTag::new);
        //#else
        //$$ return compound.contains(key, 10) ? compound.getCompound(key) : new NbtCompound();
        //#endif
    }

    public static String string(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getStringOr(key, "");
        //#else
        //$$ return compound.getString(key);
        //#endif
    }

    public static int integer(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getIntOr(key, 0);
        //#else
        //$$ return compound.getInt(key);
        //#endif
    }

    public static int byteValue(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getByteOr(key, (byte) 0);
        //#else
        //$$ return compound.getByte(key);
        //#endif
    }

    public static long longValue(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getLongOr(key, 0L);
        //#else
        //$$ return compound.getLong(key);
        //#endif
    }

    public static long[] longArray(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getLongArray(key).orElseGet(() -> new long[0]);
        //#else
        //$$ return compound.getLongArray(key);
        //#endif
    }

    public static int[] intArray(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getIntArray(key).orElseGet(() -> new int[0]);
        //#else
        //$$ return compound.getIntArray(key);
        //#endif
    }

    public static byte[] byteArray(final CompoundTag compound, final String key) {
        //#if MC>=12105
        return compound.getByteArray(key).orElseGet(() -> new byte[0]);
        //#else
        //$$ return compound.getByteArray(key);
        //#endif
    }

    public static ListTag list(final CompoundTag compound, final String key, final int elementType) {
        //#if MC>=12105
        return compound.getList(key).orElseGet(ListTag::new);
        //#else
        //$$ return compound.contains(key, 9) ? compound.getList(key, elementType) : new NbtList();
        //#endif
    }

    public static CompoundTag compound(final ListTag list, final int index) {
        //#if MC>=12105
        return list.getCompound(index).orElseGet(CompoundTag::new);
        //#else
        //$$ return list.getCompound(index);
        //#endif
    }

    public static String string(final ListTag list, final int index) {
        //#if MC>=12105
        return list.getStringOr(index, "");
        //#else
        //$$ return list.getString(index);
        //#endif
    }
}

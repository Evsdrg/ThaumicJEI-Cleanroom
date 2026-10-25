package com.buuz135.thaumicjei.data;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

public final class ItemIdentity {
    private final NBTTagCompound data;
    private final int hash;
    private final String serialized;

    private ItemIdentity(NBTTagCompound data, String serialized) {
        this.data = data;
        this.hash = data.hashCode();
        this.serialized = serialized;
    }

    public static ItemIdentity of(ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalArgumentException("Empty stacks have no identity");
        var data = stack.serializeNBT().copy();
        data.setByte("Count", (byte) 1);
        var serialized = data.toString();
        data.removeTag("Count");
        return new ItemIdentity(data, serialized);
    }

    public ItemStack createStack(int count) {
        var copy = data.copy();
        copy.setByte("Count", (byte) 1);
        var stack = new ItemStack(copy);
        stack.setCount(count);
        return stack;
    }

    public String serializedNbt() { return serialized; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ItemIdentity identity && data.equals(identity.data);
    }

    @Override
    public int hashCode() {
        return hash;
    }
}

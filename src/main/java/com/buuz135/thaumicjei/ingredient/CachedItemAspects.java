package com.buuz135.thaumicjei.ingredient;

import com.buuz135.thaumicjei.data.AspectSnapshot;
import net.minecraft.item.ItemStack;

import java.util.List;
import java.util.Objects;

public final class CachedItemAspects {
    public record View(long generation, AspectSnapshot snapshot) {
        public View { Objects.requireNonNull(snapshot); }
    }

    private static volatile View current = new View(0, AspectSnapshot.empty());

    private CachedItemAspects() {}

    public static View view() { return current; }

    public static synchronized long beginReload() {
        long generation = current.generation() + 1;
        current = new View(generation, AspectSnapshot.empty());
        return generation;
    }

    public static synchronized long beginGeneration() {
        long generation = current.generation() + 1;
        current = new View(generation, current.snapshot());
        return generation;
    }

    public static synchronized boolean publish(long generation, AspectSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (generation != current.generation()) return false;
        current = new View(generation, snapshot);
        return true;
    }

    public static List<AspectSnapshot.Amount> get(ItemStack stack) {
        return switch (current.snapshot().lookup(stack)) {
            case AspectSnapshot.Known(var amounts) -> amounts;
            case AspectSnapshot.Unknown() -> List.of();
        };
    }
}

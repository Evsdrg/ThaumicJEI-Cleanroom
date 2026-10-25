package com.buuz135.thaumicjei.data;

import org.junit.jupiter.api.Test;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import thaumcraft.api.aspects.Aspect;

import static org.junit.jupiter.api.Assertions.*;

class SourcePageSafetyTest {
    @Test
    void pageAccessRejectsInvalidRangesAndExposesNoArrays() {
        Bootstrap.register();
        var snapshot = AspectSnapshot.builder().add(new ItemStack(Items.APPLE), Aspect.AIR, 5).build();
        assertThrows(IllegalArgumentException.class,
                () -> snapshot.sourceStacks(new AspectSnapshot.Page(Aspect.AIR, Integer.MAX_VALUE, 36)));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot.sourceStacks(new AspectSnapshot.Page(Aspect.AIR, 0, -1)));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot.sourceStacks(new AspectSnapshot.Page(Aspect.FIRE, 0, 1)));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.pages().clear());
        var amounts = ((AspectSnapshot.Known) snapshot.lookup(new ItemStack(Items.APPLE))).amounts();
        amounts.getFirst().asList().remove(Aspect.AIR);
        assertEquals(5, ((AspectSnapshot.Known) snapshot.lookup(new ItemStack(Items.APPLE))).amounts().getFirst().amount());
    }

    @Test
    void publicationDiffRetainsUnchangedPages() {
        Bootstrap.register();
        var stacks = new ItemStack[40];
        for (int i = 0; i < stacks.length; i++) {
            stacks[i] = new ItemStack(Items.APPLE);
            var nbt = new net.minecraft.nbt.NBTTagCompound();
            nbt.setInteger("testId", i);
            stacks[i].setTagCompound(nbt);
        }
        var previous = sources(stacks, 100);
        var next = sources(stacks, 101);
        var first = new AspectSnapshot.PageKey(Aspect.AIR, 0);
        var changes = previous.changesTo(next);
        assertEquals(java.util.List.of(first), changes.removals());
        assertEquals(1, changes.additions().size());
        assertEquals(first, next.pageKey(changes.additions().getFirst()));
        assertEquals(101, next.sourceStacks(changes.additions().getFirst()).getFirst().getCount());
        assertEquals(100, previous.sourceStacks(previous.page(first)).getFirst().getCount());
        assertEquals(4, next.pages().size());
        assertNull(next.page(new AspectSnapshot.PageKey(Aspect.AIR, 2)));
        assertNull(next.page(new AspectSnapshot.PageKey(Aspect.AIR, Integer.MAX_VALUE)));
        assertThrows(UnsupportedOperationException.class, () -> changes.additions().clear());
        assertTrue(previous.changesTo(previous).additions().isEmpty());
        assertTrue(previous.changesTo(previous).removals().isEmpty());
        assertEquals(1, next.changesTo(previous).additions().size());
        var empty = AspectSnapshot.empty();
        assertEquals(4, empty.changesTo(previous).additions().size());
        assertEquals(4, previous.changesTo(empty).removals().size());
    }

    @Test
    void serializedExportUsesCountOneWithoutMutatingSourceStack() throws Exception {
        Bootstrap.register();
        var stack = new ItemStack(Items.APPLE, 64);
        var snapshot = AspectSnapshot.builder().add(stack, Aspect.AIR, 5).build();
        var stored = snapshot.exportItems().getFirst();
        assertEquals(1, new ItemStack(net.minecraft.nbt.JsonToNBT.getTagFromJson(stored.nbt())).getCount());
        assertEquals(64, stack.getCount());
    }

    private static AspectSnapshot sources(ItemStack[] stacks, int topAmount) {
        var builder = AspectSnapshot.builder();
        for (int i = 0; i < stacks.length; i++) {
            builder.add(stacks[i], Aspect.AIR, i == 0 ? topAmount : 100 - i);
            builder.add(stacks[i], Aspect.WATER, 1);
        }
        return builder.build();
    }
}

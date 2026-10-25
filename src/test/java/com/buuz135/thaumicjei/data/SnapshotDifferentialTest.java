package com.buuz135.thaumicjei.data;

import org.junit.jupiter.api.Test;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import thaumcraft.api.aspects.Aspect;

import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotDifferentialTest {
    @Test
    void randomizedRelationsMatchDenseReference() {
        Bootstrap.register();
        var random = new Random(20261025);
        var aspects = new Aspect[]{Aspect.AIR, Aspect.FIRE, Aspect.WATER, Aspect.EARTH};
        for (int round = 0; round < 25; round++) {
            var builder = AspectSnapshot.builder();
            var stacks = new ItemStack[64];
            var expected = new int[64][aspects.length];
            for (int i = 0; i < stacks.length; i++) {
                stacks[i] = new ItemStack(Items.APPLE);
                var nbt = new NBTTagCompound(); nbt.setInteger("id", i); stacks[i].setTagCompound(nbt);
            }
            for (int operation = 0; operation < 500; operation++) {
                int item = random.nextInt(stacks.length), aspect = random.nextInt(aspects.length), amount = random.nextInt(500) + 1;
                builder.add(stacks[item], aspects[aspect], amount);
                expected[item][aspect] = amount;
            }
            var snapshot = builder.build();
            int relations = 0;
            for (int i = 0; i < stacks.length; i++) {
                var actual = new int[aspects.length];
                switch (snapshot.lookup(stacks[i])) {
                    case AspectSnapshot.Unknown() -> { }
                    case AspectSnapshot.Known(var amounts) -> {
                        for (var amount : amounts) for (int a = 0; a < aspects.length; a++)
                            if (amount.aspect() == aspects[a]) actual[a] = amount.amount();
                    }
                }
                assertArrayEquals(expected[i], actual);
                for (int amount : expected[i]) if (amount > 0) relations++;
            }
            assertEquals(relations, snapshot.stats().relations());
            for (var page : snapshot.pages()) {
                assertTrue(page.size() <= 36);
                for (var stack : snapshot.sourceStacks(page)) {
                    int id = stack.getTagCompound().getInteger("id");
                    int a = java.util.Arrays.asList(aspects).indexOf(page.aspect());
                    assertEquals(expected[id][a], stack.getCount());
                }
            }
        }
    }
}

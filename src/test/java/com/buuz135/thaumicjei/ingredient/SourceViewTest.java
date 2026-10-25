package com.buuz135.thaumicjei.ingredient;

import com.buuz135.thaumicjei.ThaumcraftJEIPlugin;
import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.category.AspectFromItemStackCategory;
import com.buuz135.thaumicjei.data.AspectSnapshot;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import thaumcraft.api.aspects.Aspect;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SourceViewTest {
    @BeforeAll
    static void bootstrap() {
        ThaumicJEI.LOGGER = LogManager.getLogger("SourceViewTest");
        Bootstrap.register();
    }

    @Test
    void wrapperSuppliesIndependentStacksThroughHeiApi() {
        var snapshot = AspectSnapshot.builder().add(new ItemStack(Items.APPLE), Aspect.AIR, 4).build();
        var wrapper = new AspectFromItemStackCategory.AspectFromItemStackWrapper(snapshot, snapshot.pages().getFirst());
        var captured = new AtomicReference<List<ItemStack>>();
        var ingredients = (mezz.jei.api.ingredients.IIngredients) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{mezz.jei.api.ingredients.IIngredients.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("setInputs") && args[0] == mezz.jei.api.ingredients.VanillaTypes.ITEM)
                        captured.set((List<ItemStack>) args[1]);
                    return null;
                });
        wrapper.getIngredients(ingredients);
        assertEquals(4, captured.get().getFirst().getCount());
        captured.get().getFirst().setCount(99);
        wrapper.getIngredients(ingredients);
        assertEquals(4, captured.get().getFirst().getCount());
    }

    @Test
    void syntheticRelationsMatchReferenceAndReportPayload() {
        int items = 2_000, aspectsPerItem = 6;
        var aspects = List.of(Aspect.AIR, Aspect.FIRE, Aspect.WATER, Aspect.EARTH, Aspect.ORDER, Aspect.ENTROPY);
        var reference = new ArrayList<Map.Entry<ItemStack, Integer>>();
        var builder = AspectSnapshot.builder();
        long start = System.nanoTime();
        for (int i = 0; i < items; i++) {
            var stack = new ItemStack(Items.APPLE);
            var tag = new net.minecraft.nbt.NBTTagCompound(); tag.setInteger("variant", i); stack.setTagCompound(tag);
            for (var aspect : aspects) builder.add(stack, aspect, i % 500 + 1);
            reference.add(Map.entry(stack, i % 500 + 1));
        }
        var snapshot = builder.build();
        long built = System.nanoTime();
        assertEquals(items, snapshot.stats().items());
        assertEquals(items * aspectsPerItem, snapshot.stats().relations());
        for (var entry : reference) {
            var result = assertInstanceOf(AspectSnapshot.Known.class, snapshot.lookup(entry.getKey()));
            assertEquals(aspectsPerItem, result.amounts().size());
            for (var amount : result.amounts()) assertEquals(entry.getValue(), amount.amount());
        }
        for (var aspect : aspects) {
            var sources = snapshot.pages().stream().filter(page -> page.aspect() == aspect)
                    .flatMap(page -> snapshot.sourceStacks(page).stream()).toList();
            assertEquals(items, sources.size());
            for (int i = 1; i < sources.size(); i++) assertTrue(sources.get(i - 1).getCount() >= sources.get(i).getCount());
        }
        System.out.printf("SNAPSHOT items=%d relations=%d relation_payload=%d build_ms=%.3f%n",
                items, snapshot.stats().relations(), snapshot.stats().relationPayloadBytes(), (built - start) / 1e6);
    }
}

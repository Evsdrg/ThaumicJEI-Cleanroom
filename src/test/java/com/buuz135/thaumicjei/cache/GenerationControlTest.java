package com.buuz135.thaumicjei.cache;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collection;
import static org.junit.jupiter.api.Assertions.*;

class GenerationControlTest {
    private static Object field(String name) throws Exception {
        var field = GenerationManager.class.getDeclaredField(name); field.setAccessible(true); return field.get(null);
    }
    private static void set(String name, Object value) throws Exception {
        var field = GenerationManager.class.getDeclaredField(name); field.setAccessible(true); field.set(null, value);
    }
    @Test
    void cancelDropsQueuedAndInFlightWorkWithoutRemovingPublishedSnapshot() throws Exception {
        Bootstrap.register();
        GenerationManager.cancel();
        set("background", new ArrayDeque<>(java.util.List.of(new ItemStack(Items.APPLE))));
        GenerationManager.pause(); assertTrue(GenerationManager.status().startsWith("paused"));
        GenerationManager.cancel();
        assertTrue(((Collection<?>) field("background")).isEmpty());
        assertNull(field("session"));
        assertNull(field("read"));
        assertTrue(GenerationManager.status().startsWith("cancelled"));
        GenerationManager.resume(); assertTrue(GenerationManager.status().startsWith("cancelled"));
    }

    @Test
    void prepareAdvancesPublishedGenerationWithoutExternalHelp() throws Exception {
        Bootstrap.register();
        GenerationManager.cancel();
        long before = com.buuz135.thaumicjei.ingredient.CachedItemAspects.view().generation();
        GenerationManager.prepare(com.buuz135.thaumicjei.recipe.OrderedRecipeIndex.capture(),
                java.util.List.of(new ItemStack(Items.APPLE)));
        assertEquals(before + 1, com.buuz135.thaumicjei.ingredient.CachedItemAspects.view().generation());
        assertEquals(before + 1, field("epoch"));
        assertTrue(GenerationManager.status().startsWith("waiting for runtime"));
        GenerationManager.cancel();
    }

    @Test
    void rebuildGenerationKeepsPreviousSnapshotAndRejectsOldPublisher() {
        Bootstrap.register();
        var cached = com.buuz135.thaumicjei.ingredient.CachedItemAspects.view();
        var snapshot = com.buuz135.thaumicjei.data.AspectSnapshot.builder()
                .add(new ItemStack(Items.APPLE), thaumcraft.api.aspects.Aspect.AIR, 5).build();
        com.buuz135.thaumicjei.ingredient.CachedItemAspects.publish(cached.generation(), snapshot);
        long generation = com.buuz135.thaumicjei.ingredient.CachedItemAspects.beginGeneration();
        assertSame(snapshot, com.buuz135.thaumicjei.ingredient.CachedItemAspects.view().snapshot());
        assertFalse(com.buuz135.thaumicjei.ingredient.CachedItemAspects.publish(cached.generation(), snapshot));
        assertEquals(generation, com.buuz135.thaumicjei.ingredient.CachedItemAspects.view().generation());
    }

    @Test
    void fixedImportDoesNotFilterSeedsOrStartEvaluator() throws Exception {
        Bootstrap.register();
        com.buuz135.thaumicjei.ThaumicJEI.LOGGER = org.apache.logging.log4j.LogManager.getLogger("fixed-cache-test");
        GenerationManager.prepare(com.buuz135.thaumicjei.recipe.OrderedRecipeIndex.capture(),
                java.util.List.of(new ItemStack(Items.APPLE)));
        var previousRuntime = com.buuz135.thaumicjei.ThaumcraftJEIPlugin.runtime;
        var runtime = (mezz.jei.api.IJeiRuntime) java.lang.reflect.Proxy.newProxyInstance(
                mezz.jei.api.IJeiRuntime.class.getClassLoader(), new Class[]{mezz.jei.api.IJeiRuntime.class},
                (proxy, method, args) -> null);
        try {
            com.buuz135.thaumicjei.ThaumcraftJEIPlugin.runtime = runtime;
            set("runtime", runtime);
            var item = new ItemStack(Items.DIAMOND);
            var stored = new com.buuz135.thaumicjei.data.AspectSnapshot.StoredItem(item.serializeNBT().toString(), java.util.Map.of("aer", 5));
            set("read", java.util.concurrent.CompletableFuture.completedFuture(new SnapshotStore.Hit(
                    new SnapshotStore.Document("different-environment", java.util.List.of(stored), true))));
            GenerationManager.tick(new net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent(
                    net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END));
            assertNull(field("evaluator"));
            assertFalse((Boolean) field("generating"));
            assertTrue(((Collection<?>) field("background")).isEmpty());
            assertInstanceOf(com.buuz135.thaumicjei.data.AspectSnapshot.Known.class,
                    com.buuz135.thaumicjei.ingredient.CachedItemAspects.view().snapshot().lookup(item));
            assertInstanceOf(com.buuz135.thaumicjei.data.AspectSnapshot.Unknown.class,
                    com.buuz135.thaumicjei.ingredient.CachedItemAspects.view().snapshot().lookup(new ItemStack(Items.APPLE)));
        } finally {
            GenerationManager.cancel();
            com.buuz135.thaumicjei.ThaumcraftJEIPlugin.runtime = previousRuntime;
        }
    }

    @Test
    void completedGenerationPublishesOnlyAfterAtomicSave(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        Bootstrap.register();
        com.buuz135.thaumicjei.ThaumicJEI.LOGGER = org.apache.logging.log4j.LogManager.getLogger("fixed-cache-test");
        GenerationManager.prepare(com.buuz135.thaumicjei.recipe.OrderedRecipeIndex.capture(), java.util.List.of());
        var previousRuntime = com.buuz135.thaumicjei.ThaumcraftJEIPlugin.runtime;
        var runtime = (mezz.jei.api.IJeiRuntime) java.lang.reflect.Proxy.newProxyInstance(
                mezz.jei.api.IJeiRuntime.class.getClassLoader(), new Class[]{mezz.jei.api.IJeiRuntime.class},
                (proxy, method, args) -> null);
        try {
            com.buuz135.thaumicjei.ThaumcraftJEIPlugin.runtime = runtime; set("runtime", runtime);
            var oldSnapshot = com.buuz135.thaumicjei.data.AspectSnapshot.builder().knownEmpty(new ItemStack(Items.APPLE)).build();
            com.buuz135.thaumicjei.ingredient.CachedItemAspects.publish((Long) field("epoch"), oldSnapshot);
            var destination = directory.resolve("snapshot.json"); set("file", destination);
            set("builder", com.buuz135.thaumicjei.data.AspectSnapshot.builder().knownEmpty(new ItemStack(Items.DIAMOND)));
            set("completed", 1);
            var finish = GenerationManager.class.getDeclaredMethod("finishGeneration"); finish.setAccessible(true); finish.invoke(null);
            assertSame(oldSnapshot, com.buuz135.thaumicjei.ingredient.CachedItemAspects.view().snapshot());
            ((java.util.concurrent.Future<?>) field("write")).get(10, java.util.concurrent.TimeUnit.SECONDS);
            GenerationManager.tick(new net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent(
                    net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END));
            assertInstanceOf(SnapshotStore.Hit.class, SnapshotStore.read(destination));
            assertInstanceOf(com.buuz135.thaumicjei.data.AspectSnapshot.Known.class,
                    com.buuz135.thaumicjei.ingredient.CachedItemAspects.view().snapshot().lookup(new ItemStack(Items.DIAMOND)));
        } finally {
            GenerationManager.cancel(); com.buuz135.thaumicjei.ThaumcraftJEIPlugin.runtime = previousRuntime;
        }
    }

    @Test
    void resumeRestoresPauseStateButNotTerminalFailure() throws Exception {
        GenerationManager.cancel();
        set("cancelled", false);
        set("paused", false);
        set("failed", false);
        set("state", "generating");
        GenerationManager.pause();
        GenerationManager.resume();
        assertTrue(GenerationManager.status().startsWith("generating"));
        set("failed", true);
        GenerationManager.pause();
        GenerationManager.resume();
        assertTrue(GenerationManager.status().startsWith("paused"));
        set("failed", false);
        GenerationManager.cancel();
    }
}

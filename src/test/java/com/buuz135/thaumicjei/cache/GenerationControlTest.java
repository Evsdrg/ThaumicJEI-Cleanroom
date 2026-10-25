package com.buuz135.thaumicjei.cache;

import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.config.ThaumicConfig;
import com.buuz135.thaumicjei.data.AspectSnapshot;
import com.buuz135.thaumicjei.ingredient.CachedItemAspects;
import com.buuz135.thaumicjei.recipe.OrderedRecipeIndex;
import mezz.jei.api.IJeiRuntime;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import thaumcraft.api.aspects.Aspect;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.buuz135.thaumicjei.cache.GenerationStatus.Stage;
import static org.junit.jupiter.api.Assertions.*;

class GenerationControlTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
        ThaumicJEI.LOGGER = LogManager.getLogger("fixed-cache-test");
    }

    private static final class Fixture {
        final QueuedExecutor io = new QueuedExecutor();
        final Path cachePath;
        final CacheGeneration generation;
        final mezz.jei.api.IRecipeRegistry recipeRegistry = (mezz.jei.api.IRecipeRegistry) Proxy.newProxyInstance(
                mezz.jei.api.IRecipeRegistry.class.getClassLoader(), new Class<?>[]{mezz.jei.api.IRecipeRegistry.class},
                (proxy, method, args) -> null);
        IJeiRuntime runtime = (IJeiRuntime) Proxy.newProxyInstance(IJeiRuntime.class.getClassLoader(),
                new Class<?>[]{IJeiRuntime.class},
                (proxy, method, args) -> method.getName().equals("getRecipeRegistry") ? recipeRegistry : null);

        Fixture(Path directory) {
            cachePath = directory.resolve("snapshot.json");
            generation = new CacheGeneration(io, OrderedRecipeIndex::capture, () -> runtime, () -> cachePath);
        }

        void prepare(ItemStack... seeds) {
            generation.prepare(OrderedRecipeIndex.capture(), List.of(seeds));
        }

        void attach() {
            generation.attach(runtime);
        }

        void finishRead() {
            io.runNext();
            generation.tick();
        }

        void publishPrevious(AspectSnapshot snapshot) {
            assertTrue(CachedItemAspects.publish(generation.status().generation(), snapshot));
        }
    }

    @Test
    void cancelDropsQueuedAndInFlightWorkWithoutRemovingPublishedSnapshot(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        fixture.prepare(new ItemStack(Items.APPLE));
        var previous = AspectSnapshot.builder().knownEmpty(new ItemStack(Items.DIAMOND)).build();
        fixture.publishPrevious(previous);
        fixture.attach();
        fixture.generation.pause();
        assertTrue(fixture.generation.status().paused());

        fixture.generation.cancel();
        fixture.io.runNext();
        fixture.generation.tick();
        fixture.generation.resume();

        assertEquals(Stage.CANCELLED, fixture.generation.status().stage());
        assertEquals(0, fixture.generation.status().queuedItems());
        assertNull(fixture.generation.status().evaluation());
        assertSame(previous, CachedItemAspects.view().snapshot());
        assertFalse(Files.exists(fixture.cachePath));
    }

    @Test
    void prepareAdvancesPublishedGenerationWithoutExternalHelp(@TempDir Path directory) {
        long before = CachedItemAspects.view().generation();
        var fixture = new Fixture(directory);
        fixture.prepare(new ItemStack(Items.APPLE));

        assertEquals(before + 1, CachedItemAspects.view().generation());
        assertEquals(before + 1, fixture.generation.status().generation());
        assertEquals(Stage.WAITING_FOR_RUNTIME, fixture.generation.status().stage());
    }

    @Test
    void rebuildGenerationKeepsPreviousSnapshotAndRejectsOldPublisher() {
        var cached = CachedItemAspects.view();
        var snapshot = AspectSnapshot.builder().add(new ItemStack(Items.APPLE), Aspect.AIR, 5).build();
        CachedItemAspects.publish(cached.generation(), snapshot);

        long generation = CachedItemAspects.beginGeneration();

        assertSame(snapshot, CachedItemAspects.view().snapshot());
        assertFalse(CachedItemAspects.publish(cached.generation(), snapshot));
        assertEquals(generation, CachedItemAspects.view().generation());
    }

    @Test
    void fixedImportDoesNotFilterSeedsOrStartEvaluator(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var item = new ItemStack(Items.DIAMOND);
        var stored = new AspectSnapshot.StoredItem(item.serializeNBT().toString(), Map.of("aer", 5));
        SnapshotStore.write(fixture.cachePath, new SnapshotStore.Document("different-environment", List.of(stored), true));
        fixture.prepare(new ItemStack(Items.APPLE));
        fixture.attach();

        fixture.finishRead();

        assertTrue(fixture.generation.status().isReady());
        assertEquals(0, fixture.generation.status().queuedItems());
        assertNull(fixture.generation.status().evaluation());
        assertInstanceOf(AspectSnapshot.Known.class, CachedItemAspects.view().snapshot().lookup(item));
        assertInstanceOf(AspectSnapshot.Unknown.class,
                CachedItemAspects.view().snapshot().lookup(new ItemStack(Items.APPLE)));
    }

    @Test
    void completedGenerationPublishesOnlyAfterAtomicSave(@TempDir Path directory) {
        var fixture = new Fixture(directory);
        fixture.prepare();
        var previous = AspectSnapshot.builder().knownEmpty(new ItemStack(Items.APPLE)).build();
        fixture.publishPrevious(previous);
        fixture.attach();

        fixture.finishRead();

        assertEquals(Stage.SAVING_CACHE, fixture.generation.status().stage());
        assertSame(previous, CachedItemAspects.view().snapshot());
        assertFalse(Files.exists(fixture.cachePath));
        fixture.io.runNext();
        assertSame(previous, CachedItemAspects.view().snapshot());
        fixture.generation.tick();
        assertInstanceOf(SnapshotStore.Hit.class, SnapshotStore.read(fixture.cachePath));
        assertTrue(fixture.generation.status().isReady());
        assertNotSame(previous, CachedItemAspects.view().snapshot());
    }

    @Test
    void resumeRestoresPauseStateButNotTerminalFailure(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        fixture.prepare();
        fixture.generation.pause();
        fixture.generation.resume();
        assertEquals(Stage.WAITING_FOR_RUNTIME, fixture.generation.status().stage());
        assertFalse(fixture.generation.status().paused());

        Files.writeString(fixture.cachePath, "{");
        fixture.attach();
        fixture.finishRead();
        assertEquals(Stage.FAILED, fixture.generation.status().stage());
        fixture.generation.pause();
        fixture.generation.resume();
        assertTrue(fixture.generation.status().paused());
    }

    @Test
    void cancelAfterSaveBeforePublicationKeepsPreviousSnapshot(@TempDir Path directory) {
        var fixture = new Fixture(directory);
        fixture.prepare();
        var previous = AspectSnapshot.builder().knownEmpty(new ItemStack(Items.APPLE)).build();
        fixture.publishPrevious(previous);
        fixture.attach();
        fixture.finishRead();
        fixture.io.runNext();

        fixture.generation.cancel();
        fixture.generation.tick();

        assertSame(previous, CachedItemAspects.view().snapshot());
        assertEquals(Stage.CANCELLED, fixture.generation.status().stage());
    }

    @Test
    void incompleteCacheRetainsPreviousSnapshotAndFile(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        Files.writeString(fixture.cachePath, "{\"schema\":2,\"complete\":false,\"items\":[]}");
        byte[] previousFile = Files.readAllBytes(fixture.cachePath);
        fixture.prepare();
        var previous = AspectSnapshot.builder().knownEmpty(new ItemStack(Items.APPLE)).build();
        fixture.publishPrevious(previous);
        fixture.attach();

        fixture.finishRead();

        assertEquals(Stage.FAILED, fixture.generation.status().stage());
        assertSame(previous, CachedItemAspects.view().snapshot());
        assertArrayEquals(previousFile, Files.readAllBytes(fixture.cachePath));
    }

    @Test
    void cancelBeforeSaveRunsRetainsPreviousFile(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        SnapshotStore.write(fixture.cachePath, new SnapshotStore.Document("previous", List.of(), true));
        fixture.prepare();
        fixture.attach();
        fixture.finishRead();
        byte[] previousFile = Files.readAllBytes(fixture.cachePath);
        fixture.generation.rebuild();
        fixture.generation.tick();
        assertEquals(Stage.SAVING_CACHE, fixture.generation.status().stage());

        fixture.generation.cancel();
        fixture.io.runNext();

        assertArrayEquals(previousFile, Files.readAllBytes(fixture.cachePath));
        assertEquals(Stage.CANCELLED, fixture.generation.status().stage());
    }

    @Test
    void runtimeChangeCancelsBeforeImportPublication(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        SnapshotStore.write(fixture.cachePath, new SnapshotStore.Document("previous", List.of(), true));
        fixture.prepare();
        fixture.attach();
        fixture.io.runNext();
        fixture.runtime = null;

        fixture.generation.tick();

        assertEquals(Stage.CANCELLED, fixture.generation.status().stage());
        assertEquals(0, CachedItemAspects.view().snapshot().stats().items());
    }

    @Test
    void firstGenerationEvaluatesSeedsAndPublishesOnlyAfterSave(@TempDir Path directory) {
        try (var tc = com.buuz135.thaumicjei.testing.ThaumcraftState.cleared()) {
            var apple = new ItemStack(Items.APPLE);
            thaumcraft.api.ThaumcraftApi.registerObjectTag(apple, new thaumcraft.api.aspects.AspectList().add(Aspect.AIR, 13));
            var fixture = new Fixture(directory);
            fixture.prepare(apple);
            fixture.attach();
            fixture.finishRead();
            for (int tick = 0; fixture.generation.status().stage() == Stage.GENERATING && tick < 1_000; tick++) {
                fixture.generation.tick();
            }
            assertEquals(Stage.SAVING_CACHE, fixture.generation.status().stage());
            assertEquals(1, fixture.generation.status().completed());
            assertInstanceOf(AspectSnapshot.Unknown.class, CachedItemAspects.view().snapshot().lookup(apple));

            fixture.io.runNext();
            fixture.generation.tick();

            assertTrue(fixture.generation.status().isReady());
            assertEquals(List.of(new AspectSnapshot.Amount(Aspect.AIR, 13)), CachedItemAspects.get(apple));
            assertInstanceOf(SnapshotStore.Hit.class, SnapshotStore.read(fixture.cachePath));
        }
    }

    @Test
    void unavailableItemsAndAspectsAreSkippedWithoutAutomaticRebuild(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var apple = new ItemStack(Items.APPLE);
        var stored = List.of(
                new AspectSnapshot.StoredItem(apple.serializeNBT().toString(), Map.of("missing-aspect", 5)),
                new AspectSnapshot.StoredItem("{id:\"missing:item\",Count:1b}", Map.of("aer", 5)));
        SnapshotStore.write(fixture.cachePath, new SnapshotStore.Document("old-pack", stored, true));
        byte[] previousFile = Files.readAllBytes(fixture.cachePath);
        fixture.prepare();
        fixture.attach();

        fixture.finishRead();

        assertTrue(fixture.generation.status().isReady());
        assertEquals(2, fixture.generation.status().skippedEntries());
        assertEquals(new AspectSnapshot.Known(List.of()), CachedItemAspects.view().snapshot().lookup(apple));
        assertArrayEquals(previousFile, Files.readAllBytes(fixture.cachePath));
    }

    @Test
    void malformedItemRecordRejectsImportWithoutPublishingPartialSnapshot(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var stored = List.of(new AspectSnapshot.StoredItem("{", Map.of("aer", 5)));
        SnapshotStore.write(fixture.cachePath, new SnapshotStore.Document("bad-nbt", stored, true));
        fixture.prepare();
        var previous = AspectSnapshot.builder().knownEmpty(new ItemStack(Items.APPLE)).build();
        fixture.publishPrevious(previous);
        fixture.attach();

        fixture.finishRead();

        assertEquals(Stage.FAILED, fixture.generation.status().stage());
        assertSame(previous, CachedItemAspects.view().snapshot());
    }

    @Test
    void saveFailureKeepsPreviousSnapshot(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        fixture.prepare();
        var previous = AspectSnapshot.builder().knownEmpty(new ItemStack(Items.APPLE)).build();
        fixture.publishPrevious(previous);
        fixture.attach();
        fixture.finishRead();
        Files.createDirectory(fixture.cachePath);
        Files.writeString(fixture.cachePath.resolve("keep"), "existing directory");

        fixture.io.runNext();
        fixture.generation.tick();

        assertEquals(Stage.FAILED, fixture.generation.status().stage());
        assertSame(previous, CachedItemAspects.view().snapshot());
        assertTrue(Files.exists(fixture.cachePath.resolve("keep")));
    }

    @Test
    void successfulImportRegistersSourcePagesOnFollowingTick(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var registered = new java.util.ArrayList<String>();
        var recipeRegistry = (mezz.jei.api.IRecipeRegistry) Proxy.newProxyInstance(
                mezz.jei.api.IRecipeRegistry.class.getClassLoader(), new Class<?>[]{mezz.jei.api.IRecipeRegistry.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addRecipe")) registered.add((String) args[1]);
                    return null;
                });
        fixture.runtime = (IJeiRuntime) Proxy.newProxyInstance(IJeiRuntime.class.getClassLoader(),
                new Class<?>[]{IJeiRuntime.class}, (proxy, method, args) -> recipeRegistry);
        var stored = new AspectSnapshot.StoredItem(new ItemStack(Items.APPLE).serializeNBT().toString(), Map.of("aer", 5));
        SnapshotStore.write(fixture.cachePath, new SnapshotStore.Document("fixed", List.of(stored), true));
        fixture.prepare();
        fixture.attach();
        fixture.finishRead();
        assertTrue(registered.isEmpty());

        fixture.generation.tick();

        assertEquals(List.of(com.buuz135.thaumicjei.category.AspectFromItemStackCategory.UUID), registered);
    }

    @Test
    void disabledFirstGenerationWaitsForManualRebuild(@TempDir Path directory) {
        boolean previousSetting = ThaumicConfig.generateAspectCache;
        try {
            ThaumicConfig.generateAspectCache = false;
            var fixture = new Fixture(directory);
            fixture.prepare();
            fixture.attach();
            fixture.finishRead();
            assertEquals(Stage.CACHE_MISSING, fixture.generation.status().stage());

            fixture.generation.rebuild();
            assertEquals(Stage.GENERATING, fixture.generation.status().stage());
            fixture.generation.tick();
            assertEquals(Stage.SAVING_CACHE, fixture.generation.status().stage());
        } finally {
            ThaumicConfig.generateAspectCache = previousSetting;
        }
    }
}

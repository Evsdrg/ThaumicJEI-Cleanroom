package com.buuz135.thaumicjei.ingredient;

import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.data.AspectSnapshot;
import com.buuz135.thaumicjei.data.ItemIdentity;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import thaumcraft.api.aspects.Aspect;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CachedItemAspectsTest {
    @BeforeAll
    static void bootstrap() {
        ThaumicJEI.LOGGER = LogManager.getLogger("ThaumicJEITest");
        Bootstrap.register();
    }

    @BeforeEach
    void reset() { CachedItemAspects.beginReload(); }

    @Test
    void unknownAndKnownEmptyRemainDistinct() {
        var stack = new ItemStack(Items.APPLE);
        assertInstanceOf(AspectSnapshot.Unknown.class, AspectSnapshot.empty().lookup(stack));
        var snapshot = AspectSnapshot.builder().knownEmpty(stack).build();
        assertEquals(new AspectSnapshot.Known(List.of()), snapshot.lookup(stack));
        assertTrue(CachedItemAspects.get(stack).isEmpty());
    }

    @Test
    void exactTagsRemainDistinctAndSnapshotsOwnTheirData() {
        var first = new ItemStack(Items.APPLE, 3);
        var tag = new NBTTagCompound();
        tag.setString("variant", "first");
        first.setTagCompound(tag);
        var builder = AspectSnapshot.builder().add(first, Aspect.AIR, 7);
        var old = builder.build();
        tag.setString("variant", "second");
        builder.add(first, Aspect.FIRE, 2);
        var snapshot = builder.build();
        long epoch = CachedItemAspects.view().generation();
        assertTrue(CachedItemAspects.publish(epoch, snapshot));
        assertEquals(Aspect.FIRE, CachedItemAspects.get(first).getFirst().aspect());
        first.setCount(64);
        assertEquals(2, CachedItemAspects.get(first).getFirst().amount());
        assertInstanceOf(AspectSnapshot.Unknown.class, old.lookup(first));
        tag.setString("variant", "first");
        assertEquals(7, CachedItemAspects.get(first).getFirst().amount());
        assertThrows(UnsupportedOperationException.class, () -> CachedItemAspects.get(first).clear());
        var displayed = snapshot.sourceStacks(snapshot.pages().getFirst()).getFirst();
        displayed.setTagCompound(new NBTTagCompound());
        assertEquals("first", snapshot.sourceStacks(snapshot.pages().getFirst()).getFirst().getTagCompound().getString("variant"));
    }

    @Test
    void compoundOrderDoesNotChangeIdentityButTypesDo() {
        var a = new ItemStack(Items.APPLE);
        var b = a.copy();
        var first = new NBTTagCompound();
        first.setInteger("a", 1);
        first.setString("b", "two");
        var second = new NBTTagCompound();
        second.setString("b", "two");
        second.setInteger("a", 1);
        a.setTagCompound(first);
        b.setTagCompound(second);
        assertEquals(ItemIdentity.of(a), ItemIdentity.of(b));
        second.setShort("a", (short) 1);
        assertNotEquals(ItemIdentity.of(a), ItemIdentity.of(b));
    }

    @Test
    void generationRejectsStalePublicationAndOldReadersRemainValid() {
        var stack = new ItemStack(Items.APPLE);
        var first = AspectSnapshot.builder().add(stack, Aspect.AIR, 1).build();
        long generation = CachedItemAspects.view().generation();
        CachedItemAspects.publish(generation, first);
        var reader = CachedItemAspects.view();
        long next = CachedItemAspects.beginReload();
        assertFalse(CachedItemAspects.publish(generation, first));
        assertTrue(CachedItemAspects.publish(next, AspectSnapshot.empty()));
        assertInstanceOf(AspectSnapshot.Known.class, reader.snapshot().lookup(stack));
        assertInstanceOf(AspectSnapshot.Unknown.class, CachedItemAspects.view().snapshot().lookup(stack));
    }

    @Test
    void sourceRelationsDeduplicateAndPagesStayBounded() {
        var builder = AspectSnapshot.builder();
        for (int i = 0; i < 80; i++) {
            var stack = new ItemStack(Items.APPLE);
            var tag = new NBTTagCompound();
            tag.setInteger("variant", i);
            stack.setTagCompound(tag);
            builder.add(stack, Aspect.AIR, i + 1).add(stack, Aspect.AIR, i + 2).add(stack, Aspect.FIRE, 1);
        }
        var snapshot = builder.build();
        assertEquals(80, snapshot.stats().items());
        assertEquals(160, snapshot.stats().relations());
        assertEquals(6, snapshot.pages().size());
        var pages = snapshot.pages().stream().filter(page -> page.aspect() == Aspect.AIR).toList();
        assertEquals(List.of(36, 36, 8), pages.stream().map(AspectSnapshot.Page::size).toList());
        assertEquals(81, snapshot.sourceStacks(pages.getFirst()).getFirst().getCount());
        assertEquals(1280, snapshot.stats().relationPayloadBytes());
    }

    @Test
    void equalAmountSourcesKeepTheirOwnAspectInsertionOrder() {
        var apple = new ItemStack(Items.APPLE);
        var carrot = new ItemStack(Items.CARROT);
        var snapshot = AspectSnapshot.builder().add(apple, Aspect.AIR, 1).add(carrot, Aspect.AIR, 1)
                .add(carrot, Aspect.FIRE, 1).add(apple, Aspect.FIRE, 1).build();
        var firePage = snapshot.pages().stream().filter(page -> page.aspect() == Aspect.FIRE).findFirst().orElseThrow();
        assertSame(Items.CARROT, snapshot.sourceStacks(firePage).getFirst().getItem());
    }

    @Test
    void publicationCanRaceWithSnapshotReaders() throws Exception {
        var snapshot = AspectSnapshot.builder().add(new ItemStack(Items.APPLE), Aspect.AIR, 2).build();
        var error = new AtomicReference<Throwable>();
        var start = new CountDownLatch(1);
        var reader = Thread.ofPlatform().start(() -> {
            try {
                start.await();
                for (int i = 0; i < 10_000; i++) {
                    var view = CachedItemAspects.view();
                    int size = view.snapshot().stats().items();
                    if (size != 0 && size != 1) throw new AssertionError("Partial snapshot");
                }
            } catch (Throwable exception) { error.set(exception); }
        });
        start.countDown();
        for (int i = 0; i < 500; i++) CachedItemAspects.publish(CachedItemAspects.beginReload(), snapshot);
        reader.join();
        assertNull(error.get());
    }
}

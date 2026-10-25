package com.buuz135.thaumicjei.cache;

import com.buuz135.thaumicjei.ThaumcraftJEIPlugin;
import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.category.AspectFromItemStackCategory;
import com.buuz135.thaumicjei.compute.AspectEvaluator;
import com.buuz135.thaumicjei.config.ThaumicConfig;
import com.buuz135.thaumicjei.data.AspectSnapshot;
import com.buuz135.thaumicjei.data.ItemIdentity;
import com.buuz135.thaumicjei.ingredient.CachedItemAspects;
import com.buuz135.thaumicjei.recipe.OrderedRecipeIndex;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import thaumcraft.api.aspects.Aspect;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

@Mod.EventBusSubscriber(modid = ThaumicJEI.MOD_ID, value = Side.CLIENT)
public final class GenerationManager {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(runnable -> {
        var thread = new Thread(runnable, "ThaumicJEI Cache IO"); thread.setDaemon(true); return thread;
    });
    private static final AtomicLong revision = new AtomicLong();
    private static final LinkedHashMap<AspectSnapshot.PageKey, IRecipeWrapper> dynamicPages = new LinkedHashMap<>();
    private static final ArrayDeque<AspectSnapshot.PageKey> removePages = new ArrayDeque<>();
    private static final ArrayDeque<AspectSnapshot.Page> addPages = new ArrayDeque<>();
    private static AspectSnapshot pageSnapshot;
    private static AspectSnapshot pendingPublication;
    private static long epoch, lastProgressNanos;
    private static OrderedRecipeIndex index;
    private static List<ItemStack> seeds = List.of();
    private static IJeiRuntime runtime;
    private static Future<SnapshotStore.ReadResult> read;
    private static Future<?> write;
    private static Path file;
    private static AspectEvaluator evaluator;
    private static AspectEvaluator.Session session;
    private static ItemStack current;
    private static ArrayDeque<ItemStack> background = new ArrayDeque<>();
    private static AspectSnapshot.Builder builder;
    private static ArrayDeque<AspectSnapshot.StoredItem> imports;
    private static final HashSet<ItemIdentity> resolved = new HashSet<>();
    private static boolean paused, cancelled, failed, generating, importRejected;
    private static int completed, failures, cooldown, lastMilestone, totalEvaluatorSteps;
    private static String state = "idle", resumeState = "idle";

    private GenerationManager() {}

    public static void prepare(OrderedRecipeIndex captured, List<ItemStack> ingredients) {
        cancel(); epoch = CachedItemAspects.beginReload(); index = captured;
        seeds = List.copyOf(ingredients);
        dynamicPages.clear(); removePages.clear(); addPages.clear(); pageSnapshot = null;
        runtime = null; cancelled = paused = failed = importRejected = false;
        completed = failures = cooldown = lastMilestone = totalEvaluatorSteps = 0;
        lastProgressNanos = 0; state = "waiting for runtime";
    }

    public static void attach(IJeiRuntime next) {
        if (runtime == next) return;
        runtime = next;
        if (!ThaumicConfig.enableAspectFromItemStacks || index == null || cancelled) return;
        file = Loader.instance().getConfigDir().toPath().resolve("thaumicjei-cache-v2.json");
        var source = file;
        read = IO.submit(() -> SnapshotStore.read(source));
        state = "reading fixed cache";
    }

    public static String status() {
        var progress = session == null ? "" : ", current=" + current.getItem().getRegistryName()
                + "@" + current.getMetadata() + ", evaluation=" + session.progress();
        return state + "; completed=" + completed + ", failed=" + failures + ", queued=" + background.size()
                + ", epoch=" + epoch + progress;
    }

    public static void pause() {
        if (!paused) resumeState = state;
        paused = true; state = "paused";
    }

    public static void resume() {
        if (!cancelled && !failed) { paused = false; state = resumeState; }
    }

    public static void cancel() {
        cancelled = true;
        synchronized (revision) { revision.incrementAndGet(); }
        if (read != null) read.cancel(true);
        if (write != null) write.cancel(true);
        read = null; write = null; pendingPublication = null; session = null; evaluator = null; imports = null; builder = null;
        background.clear(); resolved.clear(); generating = false; state = "cancelled";
    }

    public static void rebuild() {
        if (index == null || runtime == null) return;
        cancel();
        epoch = CachedItemAspects.beginGeneration();
        cancelled = paused = failed = importRejected = false;
        completed = failures = cooldown = lastMilestone = totalEvaluatorSteps = 0;
        lastProgressNanos = 0;
        index = OrderedRecipeIndex.capture();
        ThaumicJEI.LOGGER.info("Manual fixed-cache rebuild; retaining the published snapshot and disk file until success");
        startGeneration();
    }

    private static void startGeneration() {
        var blacklist = new HashSet<>(Arrays.asList(ThaumicConfig.blacklistedFromAspectChecking));
        var unique = new LinkedHashMap<ItemIdentity, ItemStack>();
        for (var stack : seeds) {
            if (stack.isEmpty() || blacklist.contains(stack.getItem().getRegistryName().toString())) continue;
            if (unique.size() >= Math.min(50000, ThaumicConfig.maxCachedItems)) break;
            unique.putIfAbsent(ItemIdentity.of(stack), stack.copy());
        }
        evaluator = new AspectEvaluator(index); builder = AspectSnapshot.builder();
        background = new ArrayDeque<>(unique.values()); generating = true; state = "generating";
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || runtime == null || cancelled || paused) return;
        if (epoch != CachedItemAspects.view().generation() || runtime != ThaumcraftJEIPlugin.runtime) {
            cancel(); return;
        }
        if (hasActiveWork() && System.nanoTime() - lastProgressNanos > 10_000_000_000L) {
            lastProgressNanos = System.nanoTime();
            ThaumicJEI.LOGGER.info("Generation state: {}; steps={}", status(), totalEvaluatorSteps);
        }
        if (write != null && write.isDone()) {
            try { write.get(); if (pendingPublication != null) publish(pendingPublication); state = "ready"; }
            catch (Exception exception) { failed = true; state = "save failed; previous cache retained";
                ThaumicJEI.LOGGER.warn("Fixed cache save failed", exception); }
            write = null; pendingPublication = null;
        }
        if (read != null) {
            if (!read.isDone()) return;
            try {
                switch (read.get()) {
                    case SnapshotStore.Hit(var document) -> {
                        if (!document.complete()) {
                            failed = true; state = "incomplete cache; use /thaumicjei rebuild";
                            ThaumicJEI.LOGGER.warn("Incomplete cache retained on disk; manual rebuild required");
                        } else {
                            imports = new ArrayDeque<>(document.items()); builder = AspectSnapshot.builder();
                            state = "loading fixed cache";
                            ThaumicJEI.LOGGER.info("Fixed cache accepted: {} items; no fingerprint or recipe validation, no automatic evaluation", document.items().size());
                        }
                    }
                    case SnapshotStore.Miss() -> {
                        if (ThaumicConfig.generateAspectCache) startGeneration(); else state = "cache missing; automatic generation disabled";
                    }
                    case SnapshotStore.Invalid(var reason) -> {
                        failed = true; state = "invalid cache; use /thaumicjei rebuild";
                        ThaumicJEI.LOGGER.warn("Fixed cache rejected: {}; file retained, no automatic replacement", reason);
                    }
                }
            } catch (Exception exception) {
                failed = true; state = "cache read failed"; ThaumicJEI.LOGGER.warn("Fixed cache read failed", exception);
            }
            read = null;
        }
        if (imports != null) {
            long deadline = System.nanoTime() + budget(); int count = 0;
            while (!imports.isEmpty() && count++ < 64 && System.nanoTime() < deadline) {
                var stored = imports.removeFirst();
                try {
                    var stack = new ItemStack(JsonToNBT.getTagFromJson(stored.nbt()));
                    if (stack.isEmpty()) { failures++; continue; }
                    builder.knownEmpty(stack);
                    for (var entry : stored.aspects().entrySet()) {
                        var aspect = Aspect.getAspect(entry.getKey());
                        if (aspect == null) { failures++; continue; }
                        builder.add(stack, aspect, entry.getValue());
                    }
                } catch (Exception exception) { failures++; importRejected = true; }
            }
            if (imports.isEmpty()) {
                imports = null;
                if (importRejected) {
                    builder = null; failed = true; state = "invalid item records; use /thaumicjei rebuild";
                } else {
                    publish(builder.build()); builder = null;
                    completed = CachedItemAspects.view().snapshot().stats().items();
                    state = failures == 0 ? "ready" : "ready; unavailable items or aspects skipped";
                    ThaumicJEI.LOGGER.info("Fixed cache loaded: {} items, {} unavailable entries; evaluator not started", completed, failures);
                }
            }
            return;
        }
        if (!removePages.isEmpty() || !addPages.isEmpty()) {
            updatePages(); return;
        }
        if (!generating || evaluator == null || builder == null) return;
        if (cooldown-- > 0) return;
        long deadline = System.nanoTime() + budget(); int started = 0;
        while (started < 8 && System.nanoTime() < deadline) {
            if (session == null) {
                current = background.pollFirst();
                if (current == null) { finishGeneration(); return; }
                var identity = ItemIdentity.of(current);
                if (!resolved.add(identity)) continue;
                session = evaluator.session(current); started++;
            }
            long start = System.nanoTime();
            var result = session.advance(1, deadline);
            long elapsed = System.nanoTime() - start;
            switch (result) {
                case AspectEvaluator.Running(var steps) -> totalEvaluatorSteps++;
                case AspectEvaluator.Complete(var value) -> {
                    builder.knownEmpty(current);
                    for (var aspect : value.getAspects()) if (value.getAmount(aspect) > 0)
                        builder.add(current, aspect, value.getAmount(aspect));
                    completed++; session = null;
                }
                case AspectEvaluator.Failed(var reason) -> {
                    failures++; session = null;
                    if (failures <= 10) ThaumicJEI.LOGGER.warn("Skipped aspect calculation: {}", reason);
                }
            }
            if (elapsed > (long) ThaumicConfig.slowCallbackMillis * 1_000_000) {
                pause(); ThaumicJEI.LOGGER.warn("Generation paused after slow callback: {} ms", elapsed / 1_000_000); return;
            }
        }
        cooldown = 1;
        if (completed >= lastMilestone + 512) {
            lastMilestone = completed;
            ThaumicJEI.LOGGER.info("Generated {} items ({} queued remaining, {} skipped, {} continuation steps)",
                    completed, background.size(), failures, totalEvaluatorSteps);
        }
    }

    private static void finishGeneration() {
        generating = false; evaluator = null; session = null; resolved.clear();
        ThaumicJEI.LOGGER.info("Evaluation queue drained; {} completed, {} skipped", completed, failures);
        if (failures != 0) {
            builder = null; failed = true; state = "generation incomplete; previous cache retained"; return;
        }
        var snapshot = builder.build(); builder = null;
        pendingPublication = snapshot;
        var destination = file; long expectedRevision = revision.get(), expectedEpoch = epoch;
        state = "saving complete fixed cache";
        write = IO.submit(() -> {
            var document = new SnapshotStore.Document("fixed-cache-java25-v1", snapshot.exportItems(), true);
            try {
                SnapshotStore.write(destination, document, () -> expectedRevision == revision.get()
                        && expectedEpoch == CachedItemAspects.view().generation(), revision);
                ThaumicJEI.LOGGER.info("Saved complete fixed cache: {} items", document.items().size());
            } catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
        });
    }

    private static void updatePages() {
        long deadline = System.nanoTime() + Math.min(budget(), 500_000L);
        var registry = runtime.getRecipeRegistry();
        while (System.nanoTime() < deadline && (!removePages.isEmpty() || !addPages.isEmpty())) {
            if (!removePages.isEmpty()) {
                var wrapper = dynamicPages.remove(removePages.removeFirst());
                if (wrapper != null) registry.removeRecipe(wrapper, AspectFromItemStackCategory.UUID);
            } else {
                var page = addPages.removeFirst();
                var wrapper = new AspectFromItemStackCategory.AspectFromItemStackWrapper(pageSnapshot, page);
                registry.addRecipe(wrapper, AspectFromItemStackCategory.UUID);
                dynamicPages.put(pageSnapshot.pageKey(page), wrapper);
            }
        }
    }

    private static void publish(AspectSnapshot snapshot) {
        if (runtime != ThaumcraftJEIPlugin.runtime || !CachedItemAspects.publish(epoch, snapshot)) return;
        var changes = pageSnapshot == null ? new AspectSnapshot.PageChanges(List.of(), snapshot.pages())
                : pageSnapshot.changesTo(snapshot);
        pageSnapshot = snapshot;
        removePages.addAll(changes.removals()); addPages.addAll(changes.additions());
        ThaumicJEI.LOGGER.info("Published fixed snapshot: {}; page removals={}, additions={}",
                snapshot.stats(), changes.removals().size(), changes.additions().size());
    }

    private static boolean hasActiveWork() {
        return read != null || imports != null || write != null || generating || !removePages.isEmpty() || !addPages.isEmpty();
    }

    private static long budget() { return (long) Math.clamp(ThaumicConfig.generationBudgetMillis, 1, 10) * 1_000_000; }
}

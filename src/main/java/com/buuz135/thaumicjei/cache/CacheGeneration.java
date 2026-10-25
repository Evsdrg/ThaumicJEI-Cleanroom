package com.buuz135.thaumicjei.cache;

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
import thaumcraft.api.aspects.Aspect;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static com.buuz135.thaumicjei.cache.GenerationStatus.Stage;

final class CacheGeneration {
    private static final int MAX_IMPORTS_PER_TICK = 64;
    private static final int MAX_SESSIONS_PER_TICK = 8;
    private static final int PROGRESS_ITEM_INTERVAL = 512;
    private static final long PROGRESS_LOG_INTERVAL_NANOS = 10_000_000_000L;
    private static final long PAGE_UPDATE_BUDGET_NANOS = 500_000L;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;

    private final ExecutorService cacheIo;
    private final Supplier<OrderedRecipeIndex> captureRecipes;
    private final Supplier<IJeiRuntime> activeRuntime;
    private final Supplier<Path> resolveCachePath;
    // Cancellation and the final file replacement must use the same lock.
    private final AtomicLong writeRevision = new AtomicLong();
    private final LinkedHashMap<AspectSnapshot.PageKey, IRecipeWrapper> registeredPages = new LinkedHashMap<>();
    private final ArrayDeque<AspectSnapshot.PageKey> pagesToRemove = new ArrayDeque<>();
    private final ArrayDeque<AspectSnapshot.Page> pagesToAdd = new ArrayDeque<>();
    private final HashSet<ItemIdentity> evaluatedItems = new HashSet<>();
    private final ArrayDeque<ItemStack> pendingItems = new ArrayDeque<>();

    private Stage stage = Stage.IDLE;
    private boolean paused;
    private String statusDetail = "";
    private long snapshotGeneration;
    private long lastProgressNanos;
    private OrderedRecipeIndex recipeIndex;
    private List<ItemStack> seedItems = List.of();
    private IJeiRuntime runtime;
    private Path cachePath;
    private Future<SnapshotStore.ReadResult> cacheReadFuture;
    private Future<?> cacheSaveFuture;
    private ArrayDeque<AspectSnapshot.StoredItem> pendingImports;
    private boolean hasInvalidImportRecords;
    private AspectSnapshot.Builder snapshotBuilder;
    private AspectSnapshot pageSnapshot;
    private AspectSnapshot snapshotAwaitingSave;
    private AspectEvaluator evaluator;
    private AspectEvaluator.Session evaluation;
    private ItemStack currentItem;
    private int completedItems;
    private int skippedEntries;
    private int cooldownTicks;
    private int lastLoggedItemCount;
    private int continuationSteps;

    CacheGeneration(ExecutorService cacheIo, Supplier<OrderedRecipeIndex> captureRecipes,
                    Supplier<IJeiRuntime> activeRuntime, Supplier<Path> resolveCachePath) {
        this.cacheIo = cacheIo;
        this.captureRecipes = captureRecipes;
        this.activeRuntime = activeRuntime;
        this.resolveCachePath = resolveCachePath;
    }

    void prepare(OrderedRecipeIndex captured, List<ItemStack> ingredients) {
        cancel();
        snapshotGeneration = CachedItemAspects.beginReload();
        recipeIndex = captured;
        seedItems = List.copyOf(ingredients);
        registeredPages.clear();
        pagesToRemove.clear();
        pagesToAdd.clear();
        pageSnapshot = null;
        runtime = null;
        resetProgress();
        transitionTo(Stage.WAITING_FOR_RUNTIME);
    }

    void attach(IJeiRuntime next) {
        if (runtime == next) return;
        runtime = next;
        if (!ThaumicConfig.enableAspectFromItemStacks || recipeIndex == null || stage == Stage.CANCELLED) return;
        cachePath = resolveCachePath.get();
        var source = cachePath;
        cacheReadFuture = cacheIo.submit(() -> SnapshotStore.read(source));
        transitionTo(Stage.READING_CACHE);
    }

    GenerationStatus status() {
        var progress = evaluation == null ? null : new GenerationStatus.Evaluation(
                String.valueOf(currentItem.getItem().getRegistryName()), currentItem.getMetadata(), evaluation.progress());
        return new GenerationStatus(stage, paused, completedItems, skippedEntries, pendingItems.size(),
                snapshotGeneration, statusDetail, progress);
    }

    void pause() {
        paused = true;
    }

    void resume() {
        if (stage != Stage.CANCELLED && stage != Stage.FAILED) paused = false;
    }

    void cancel() {
        synchronized (writeRevision) {
            writeRevision.incrementAndGet();
        }
        if (cacheReadFuture != null) cacheReadFuture.cancel(true);
        if (cacheSaveFuture != null) cacheSaveFuture.cancel(true);
        cacheReadFuture = null;
        cacheSaveFuture = null;
        snapshotAwaitingSave = null;
        evaluation = null;
        evaluator = null;
        pendingImports = null;
        snapshotBuilder = null;
        pendingItems.clear();
        evaluatedItems.clear();
        paused = false;
        transitionTo(Stage.CANCELLED);
    }

    void rebuild() {
        if (recipeIndex == null || runtime == null) return;
        cancel();
        snapshotGeneration = CachedItemAspects.beginGeneration();
        resetProgress();
        recipeIndex = captureRecipes.get();
        ThaumicJEI.LOGGER.info("Manual fixed-cache rebuild; retaining the published snapshot and disk file until success");
        startGeneration();
    }

    void tick() {
        if (runtime == null || stage == Stage.CANCELLED || paused) return;
        if (snapshotGeneration != CachedItemAspects.view().generation() || runtime != activeRuntime.get()) {
            cancel();
            return;
        }
        logProgressIfDue();
        pollCacheSave();
        if (!pollCacheRead()) return;
        if (pendingImports != null) {
            importCacheSlice();
            return;
        }
        if (!pagesToRemove.isEmpty() || !pagesToAdd.isEmpty()) {
            updateSourcePages();
            return;
        }
        if (stage != Stage.GENERATING) return;
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }
        evaluateSlice();
    }

    private void resetProgress() {
        paused = false;
        hasInvalidImportRecords = false;
        completedItems = 0;
        skippedEntries = 0;
        cooldownTicks = 0;
        lastLoggedItemCount = 0;
        continuationSteps = 0;
        lastProgressNanos = 0;
    }

    private void transitionTo(Stage next) {
        transitionTo(next, "");
    }

    private void transitionTo(Stage next, String detail) {
        stage = next;
        statusDetail = detail;
    }

    private void startGeneration() {
        var blacklist = new HashSet<>(Arrays.asList(ThaumicConfig.blacklistedFromAspectChecking));
        var uniqueItems = new LinkedHashMap<ItemIdentity, ItemStack>();
        for (var stack : seedItems) {
            if (stack.isEmpty() || blacklist.contains(stack.getItem().getRegistryName().toString())) continue;
            if (uniqueItems.size() >= Math.min(SnapshotStore.MAX_ITEMS, ThaumicConfig.maxCachedItems)) break;
            uniqueItems.putIfAbsent(ItemIdentity.of(stack), stack.copy());
        }
        evaluator = new AspectEvaluator(recipeIndex);
        snapshotBuilder = AspectSnapshot.builder();
        pendingItems.clear();
        pendingItems.addAll(uniqueItems.values());
        transitionTo(Stage.GENERATING);
    }

    private void pollCacheSave() {
        if (cacheSaveFuture == null || !cacheSaveFuture.isDone()) return;
        try {
            cacheSaveFuture.get();
            if (snapshotAwaitingSave != null) publish(snapshotAwaitingSave);
            transitionTo(Stage.READY);
        } catch (Exception exception) {
            transitionTo(Stage.FAILED, "save failed; previous cache retained");
            ThaumicJEI.LOGGER.warn("Fixed cache save failed", exception);
        }
        cacheSaveFuture = null;
        snapshotAwaitingSave = null;
    }

    private boolean pollCacheRead() {
        if (cacheReadFuture == null) return true;
        if (!cacheReadFuture.isDone()) return false;
        try {
            switch (cacheReadFuture.get()) {
                case SnapshotStore.Hit(var document) -> beginImport(document);
                case SnapshotStore.Miss() -> {
                    if (ThaumicConfig.generateAspectCache) startGeneration();
                    else transitionTo(Stage.CACHE_MISSING);
                }
                case SnapshotStore.Invalid(var reason) -> {
                    transitionTo(Stage.FAILED, "invalid cache; use /thaumicjei rebuild");
                    ThaumicJEI.LOGGER.warn("Fixed cache rejected: {}; file retained, no automatic replacement", reason);
                }
            }
        } catch (Exception exception) {
            transitionTo(Stage.FAILED, "cache read failed");
            ThaumicJEI.LOGGER.warn("Fixed cache read failed", exception);
        }
        cacheReadFuture = null;
        return true;
    }

    private void beginImport(SnapshotStore.Document document) {
        if (!document.complete()) {
            transitionTo(Stage.FAILED, "incomplete cache; use /thaumicjei rebuild");
            ThaumicJEI.LOGGER.warn("Incomplete cache retained on disk; manual rebuild required");
            return;
        }
        pendingImports = new ArrayDeque<>(document.items());
        snapshotBuilder = AspectSnapshot.builder();
        transitionTo(Stage.IMPORTING_CACHE);
        ThaumicJEI.LOGGER.info("Fixed cache accepted: {} items; no fingerprint or recipe validation, no automatic evaluation",
                document.items().size());
    }

    private void importCacheSlice() {
        long deadline = System.nanoTime() + budgetNanos();
        int imported = 0;
        while (!pendingImports.isEmpty() && imported++ < MAX_IMPORTS_PER_TICK && System.nanoTime() < deadline) {
            importItem(pendingImports.removeFirst());
        }
        if (!pendingImports.isEmpty()) return;
        pendingImports = null;
        if (hasInvalidImportRecords) {
            snapshotBuilder = null;
            transitionTo(Stage.FAILED, "invalid item records; use /thaumicjei rebuild");
            return;
        }
        publish(snapshotBuilder.build());
        snapshotBuilder = null;
        completedItems = CachedItemAspects.view().snapshot().stats().items();
        transitionTo(Stage.READY, skippedEntries == 0 ? "" : "ready; unavailable items or aspects skipped");
        ThaumicJEI.LOGGER.info("Fixed cache loaded: {} items, {} unavailable entries; evaluator not started",
                completedItems, skippedEntries);
    }

    private void importItem(AspectSnapshot.StoredItem stored) {
        try {
            var stack = new ItemStack(JsonToNBT.getTagFromJson(stored.nbt()));
            if (stack.isEmpty()) {
                skippedEntries++;
                return;
            }
            snapshotBuilder.knownEmpty(stack);
            for (var entry : stored.aspects().entrySet()) {
                var aspect = Aspect.getAspect(entry.getKey());
                if (aspect == null) {
                    skippedEntries++;
                    continue;
                }
                snapshotBuilder.add(stack, aspect, entry.getValue());
            }
        } catch (Exception exception) {
            skippedEntries++;
            hasInvalidImportRecords = true;
        }
    }

    private void evaluateSlice() {
        long deadline = System.nanoTime() + budgetNanos();
        int startedSessions = 0;
        while (startedSessions < MAX_SESSIONS_PER_TICK && System.nanoTime() < deadline) {
            if (evaluation == null) {
                currentItem = pendingItems.pollFirst();
                if (currentItem == null) {
                    finishGeneration();
                    return;
                }
                if (!evaluatedItems.add(ItemIdentity.of(currentItem))) continue;
                evaluation = evaluator.session(currentItem);
                startedSessions++;
            }
            long started = System.nanoTime();
            var result = evaluation.advance(1, deadline);
            long elapsed = System.nanoTime() - started;
            acceptEvaluation(result);
            if (elapsed > (long) ThaumicConfig.slowCallbackMillis * NANOS_PER_MILLISECOND) {
                pause();
                ThaumicJEI.LOGGER.warn("Generation paused after slow callback: {} ms", elapsed / NANOS_PER_MILLISECOND);
                return;
            }
        }
        cooldownTicks = 1;
        if (completedItems >= lastLoggedItemCount + PROGRESS_ITEM_INTERVAL) {
            lastLoggedItemCount = completedItems;
            ThaumicJEI.LOGGER.info("Generated {} items ({} queued remaining, {} skipped, {} continuation steps)",
                    completedItems, pendingItems.size(), skippedEntries, continuationSteps);
        }
    }

    private void acceptEvaluation(AspectEvaluator.Outcome result) {
        switch (result) {
            case AspectEvaluator.Running(var steps) -> continuationSteps++;
            case AspectEvaluator.Complete(var aspects) -> {
                snapshotBuilder.knownEmpty(currentItem);
                for (var aspect : aspects.getAspects()) {
                    if (aspects.getAmount(aspect) > 0) snapshotBuilder.add(currentItem, aspect, aspects.getAmount(aspect));
                }
                completedItems++;
                evaluation = null;
            }
            case AspectEvaluator.Failed(var reason) -> {
                skippedEntries++;
                evaluation = null;
                if (skippedEntries <= 10) ThaumicJEI.LOGGER.warn("Skipped aspect calculation: {}", reason);
            }
        }
    }

    private void finishGeneration() {
        evaluator = null;
        evaluation = null;
        evaluatedItems.clear();
        ThaumicJEI.LOGGER.info("Evaluation queue drained; {} completed, {} skipped", completedItems, skippedEntries);
        if (skippedEntries != 0) {
            snapshotBuilder = null;
            transitionTo(Stage.FAILED, "generation incomplete; previous cache retained");
            return;
        }
        var snapshot = snapshotBuilder.build();
        snapshotBuilder = null;
        snapshotAwaitingSave = snapshot;
        var destination = cachePath;
        long expectedRevision = writeRevision.get();
        long expectedGeneration = snapshotGeneration;
        transitionTo(Stage.SAVING_CACHE);
        cacheSaveFuture = cacheIo.submit(() -> {
            var document = new SnapshotStore.Document("fixed-cache-java25-v1", snapshot.exportItems(), true);
            try {
                SnapshotStore.write(destination, document, () -> expectedRevision == writeRevision.get()
                        && expectedGeneration == CachedItemAspects.view().generation(), writeRevision);
                ThaumicJEI.LOGGER.info("Saved complete fixed cache: {} items", document.items().size());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        });
    }

    private void updateSourcePages() {
        long deadline = System.nanoTime() + Math.min(budgetNanos(), PAGE_UPDATE_BUDGET_NANOS);
        var registry = runtime.getRecipeRegistry();
        while (System.nanoTime() < deadline && (!pagesToRemove.isEmpty() || !pagesToAdd.isEmpty())) {
            if (!pagesToRemove.isEmpty()) {
                var wrapper = registeredPages.remove(pagesToRemove.removeFirst());
                if (wrapper != null) registry.removeRecipe(wrapper, AspectFromItemStackCategory.UUID);
            } else {
                var page = pagesToAdd.removeFirst();
                var wrapper = new AspectFromItemStackCategory.AspectFromItemStackWrapper(pageSnapshot, page);
                registry.addRecipe(wrapper, AspectFromItemStackCategory.UUID);
                registeredPages.put(pageSnapshot.pageKey(page), wrapper);
            }
        }
    }

    private void publish(AspectSnapshot snapshot) {
        if (runtime != activeRuntime.get() || !CachedItemAspects.publish(snapshotGeneration, snapshot)) return;
        var changes = pageSnapshot == null ? new AspectSnapshot.PageChanges(List.of(), snapshot.pages())
                : pageSnapshot.changesTo(snapshot);
        pageSnapshot = snapshot;
        pagesToRemove.addAll(changes.removals());
        pagesToAdd.addAll(changes.additions());
        ThaumicJEI.LOGGER.info("Published fixed snapshot: {}; page removals={}, additions={}",
                snapshot.stats(), changes.removals().size(), changes.additions().size());
    }

    private void logProgressIfDue() {
        boolean active = cacheReadFuture != null || pendingImports != null || cacheSaveFuture != null
                || stage == Stage.GENERATING || !pagesToRemove.isEmpty() || !pagesToAdd.isEmpty();
        long now = System.nanoTime();
        if (active && now - lastProgressNanos > PROGRESS_LOG_INTERVAL_NANOS) {
            lastProgressNanos = now;
            ThaumicJEI.LOGGER.info("Generation state: {}; steps={}", status().describe(), continuationSteps);
        }
    }

    private static long budgetNanos() {
        return (long) Math.clamp(ThaumicConfig.generationBudgetMillis, 1, 10) * NANOS_PER_MILLISECOND;
    }
}

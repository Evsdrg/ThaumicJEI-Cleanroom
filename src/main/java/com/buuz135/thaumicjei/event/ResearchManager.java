/*
 * This file is part of ThaumicJEI.
 *
 * Copyright 2018, Buuz135
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in the
 * Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so, subject to the
 * following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies
 * or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.buuz135.thaumicjei.event;

import com.buuz135.thaumicjei.ThaumcraftJEIPlugin;
import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.category.IHasResearch;
import com.buuz135.thaumicjei.config.ThaumicConfig;
import com.buuz135.thaumicjei.util.ResearchUtils;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.recipe.IRecipeWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import thaumcraft.api.capabilities.ThaumcraftCapabilities;
import thaumcraft.api.research.ResearchEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps JEI recipe visibility in sync with the client player's Thaumcraft knowledge.
 * <p>
 * A {@code ResearchEvent} may be fired from another thread (for example the integrated server thread),
 * so the handler never touches JEI directly: it queues a player-bound notice and requests a refresh.
 * All player/JEI work happens on the client thread in the client tick handler, which collapses
 * any number of events into one pass, so there is no per-event full scan. {@link #requestRefresh()} is
 * thread-safe and is also what the plugin/config call when the JEI runtime or the config changes.
 * <p>
 * Only recipes whose visibility actually changes are hidden/unhidden; the plugin's initial hide pass is
 * tracked so the first pass does not re-hide everything and only reveals what the player may see.
 */
@Mod.EventBusSubscriber(modid = ThaumicJEI.MOD_ID, value = Side.CLIENT)
public class ResearchManager {

    private static final int RETRY_DELAY_TICKS = 20;
    private static final int KNOWLEDGE_RETRY_TICKS = 100;
    private static final int RECHECK_DELAY_TICKS = 20;
    private static final int MAX_RECHECKS = 10;

    /** Research keys announced by events, waiting for Thaumcraft's knowledge to confirm them. */
    private record ResearchNotice(UUID playerId, String key) {}

    private static final ConcurrentLinkedQueue<ResearchNotice> notices = new ConcurrentLinkedQueue<>();
    private static final Set<String> pendingResearchKeys = new HashSet<>();
    /** Wrappers hidden by this manager; only touched from the client thread. */
    private static final Set<IRecipeWrapper> hiddenWrappers = Collections.newSetFromMap(new IdentityHashMap<IRecipeWrapper, Boolean>());

    private static final AtomicBoolean refreshRequested = new AtomicBoolean(false);
    private static volatile IJeiRuntime observedRuntime;
    /** Ticks to wait before retrying; -1 means "no deferral". Client thread only. */
    private static int retryCountdown = -1;
    /** True while the plugin's initial "hide every Thaumcraft recipe" pass is still in effect. */
    private static boolean initialHidesAreActive;
    /** Last applied config value, so enabling/disabling is treated as a transition. */
    private static Boolean lastConfigEnabled;
    private static int recheckAttempts;

    /** Call when the JEI runtime becomes available or a relevant config value changed. Thread-safe. */
    public static void requestRefresh() {
        refreshRequested.set(true);
    }

    @SubscribeEvent
    public static void onResearch(ResearchEvent.Research event) {
        String key = event.getResearchKey();
        if (key != null && !key.isEmpty() && event.getPlayer() != null) {
            notices.add(new ResearchNotice(event.getPlayer().getUniqueID(), key.toLowerCase(Locale.ROOT)));
        }
        refreshRequested.set(true);
    }

    @SubscribeEvent
    public static void onJoin(WorldEvent.Load event) {
        if (event.getWorld() instanceof WorldClient) {
            // A (re)join brings a fresh player/knowledge; reconcile against it.
            refreshRequested.set(true);
            retryCountdown = RETRY_DELAY_TICKS;
        }
    }

    @SubscribeEvent
    public static void onLogout(WorldEvent.Unload event) {
        if (event.getWorld() instanceof WorldClient) {
            // Drop player-bound pending state; hiddenWrappers still mirrors the real JEI state.
            pendingResearchKeys.clear();
            recheckAttempts = 0;
            refreshRequested.set(false);
            retryCountdown = -1;
        }
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        refreshRuntimeIfChanged();
        EntityPlayer player = Minecraft.getMinecraft().player;
        ResearchNotice notice;
        while ((notice = notices.poll()) != null) {
            if (player != null && player.getUniqueID().equals(notice.playerId())) {
                pendingResearchKeys.add(notice.key());
                refreshRequested.set(true);
            }
        }
        if (!refreshRequested.get() && pendingResearchKeys.isEmpty()) return;
        if (retryCountdown > 0) {
            retryCountdown--;
            return;
        }
        retryCountdown = -1;
        reconcile();
    }

    private static void refreshRuntimeIfChanged() {
        IJeiRuntime runtime = ThaumcraftJEIPlugin.runtime;
        if (runtime == observedRuntime) return;
        observedRuntime = runtime;
        hiddenWrappers.clear();
        pendingResearchKeys.clear();
        recheckAttempts = 0;
        retryCountdown = -1;
        lastConfigEnabled = null;
        // The plugin hides every Thaumcraft recipe during its own init when the config is on; remember
        // that so the first pass hides nothing new and only unhides what the player may see.
        initialHidesAreActive = runtime != null && ThaumicConfig.hideRecipesIfMissingResearch;
        refreshRequested.set(true);
    }

    private static void reconcile() {
        IJeiRuntime runtime = ThaumcraftJEIPlugin.runtime;
        if (runtime == null) {
            defer(RETRY_DELAY_TICKS);
            return;
        }

        if (!ThaumicConfig.hideRecipesIfMissingResearch) {
            releaseHidden(runtime);
            lastConfigEnabled = Boolean.FALSE;
            pendingResearchKeys.clear();
            recheckAttempts = 0;
            refreshRequested.set(false);
            return;
        }

        EntityPlayer player = Minecraft.getMinecraft().player;
        if (player == null || ThaumcraftCapabilities.getKnowledge(player) == null) {
            defer(player == null ? RETRY_DELAY_TICKS : KNOWLEDGE_RETRY_TICKS);
            return;
        }

        // Everything is visible after a disabled -> enabled transition.
        if (Boolean.FALSE.equals(lastConfigEnabled)) initialHidesAreActive = false;

        // Keys announced by events act as early approval for that exact condition; Thaumcraft's own
        // knowledge is authoritative for everything else (including "a||b" and "research@stage").
        Set<String> approvedKeys = Set.copyOf(pendingResearchKeys);

        refreshRequested.set(false);
        for (var entry : ThaumcraftJEIPlugin.researchRecipes()) {
            IRecipeWrapper wrapper = entry.wrapper();
            String categoryUid = entry.categoryUid();
            if (wrapper == null || categoryUid == null || !(wrapper instanceof IHasResearch researched)) continue;

            boolean known = ResearchUtils.knowsAllResearch(player, approvedKeys, researched.getResearch());
            boolean hiddenByManager = hiddenWrappers.contains(wrapper);
            boolean currentlyHidden = hiddenByManager || initialHidesAreActive;
            if (!known) {
                // Already hidden by the plugin's initial pass: only record ownership, no JEI call.
                if (!currentlyHidden) runtime.getRecipeRegistry().hideRecipe(wrapper, categoryUid);
                hiddenWrappers.add(wrapper);
            } else if (currentlyHidden) {
                runtime.getRecipeRegistry().unhideRecipe(wrapper, categoryUid);
                hiddenWrappers.remove(wrapper);
            }
        }
        initialHidesAreActive = false;
        lastConfigEnabled = Boolean.TRUE;

        // Keep pre-approved keys until Thaumcraft reports them, so a lagging capability sync cannot
        // re-hide a recipe that was just revealed; retry a bounded number of times.
        pendingResearchKeys.removeIf(key -> ResearchUtils.knowsKeyIgnoringCase(player, key));
        if (!pendingResearchKeys.isEmpty()) {
            if (++recheckAttempts <= MAX_RECHECKS) {
                defer(RECHECK_DELAY_TICKS);
            } else {
                // Give up on the unconfirmed keys and reconcile once more against authoritative
                // knowledge only, so visibility converges instead of staying optimistically revealed.
                pendingResearchKeys.clear();
                recheckAttempts = 0;
                defer(RECHECK_DELAY_TICKS);
            }
        } else {
            recheckAttempts = 0;
        }
    }

    /** Config is off: undo the plugin's initial hides and everything this manager hid. */
    private static void releaseHidden(IJeiRuntime runtime) {
        if (!initialHidesAreActive && hiddenWrappers.isEmpty()) return;
        for (var entry : ThaumcraftJEIPlugin.researchRecipes()) {
            IRecipeWrapper wrapper = entry.wrapper();
            String categoryUid = entry.categoryUid();
            if (wrapper == null || categoryUid == null) continue;
            if (initialHidesAreActive || hiddenWrappers.contains(wrapper)) {
                runtime.getRecipeRegistry().unhideRecipe(wrapper, categoryUid);
            }
        }
        hiddenWrappers.clear();
        initialHidesAreActive = false;
    }

    private static void defer(int ticks) {
        // Keep the request alive so the client tick retries once the player/runtime is ready.
        retryCountdown = ticks;
        refreshRequested.set(true);
    }
}

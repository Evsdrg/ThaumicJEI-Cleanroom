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
package com.buuz135.thaumicjei.config;

import com.buuz135.thaumicjei.ThaumicJEI;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = ThaumicJEI.MOD_ID)
public class ThaumicConfig {

    @Config.RequiresMcRestart
    @Config.Comment("Enable snapshot-backed item aspect source pages and tooltips.")
    public static boolean enableAspectFromItemStacks = true;

    @Config.RequiresMcRestart
    @Config.Comment("Items blacklisted from the checking in the Aspect For ItemStack. Format: 'minecraft:stone'")
    public static String[] blacklistedFromAspectChecking = new String[]{};

    @Config.Comment("Legacy option ignored. Existing caches are reused until /thaumicjei rebuild.")
    public static boolean alwaysRecreateAspectFromItemStackFile = false;

    @Config.Comment("Show snapshot-only aspect tooltips. Unknown items remain unknown and never trigger calculation.")
    public static boolean forceAspectTooltipInAllGUI = true;

    @Config.Comment("Hide recipes from JEI if you don't have the research for it")
    public static boolean hideRecipesIfMissingResearch = true;

    @Config.Comment("Generate once only when no cache file exists. Existing caches are never automatically rebuilt or extended.")
    public static boolean generateAspectCache = true;

    @Config.RangeInt(min = 1, max = 10)
    @Config.Comment("Maximum cooperative evaluator time per client tick in milliseconds. Third-party callbacks cannot be preempted.")
    public static int generationBudgetMillis = 2;

    @Config.RangeInt(min = 1, max = 50000)
    public static int maxCachedItems = 20000;

    @Config.RangeInt(min = 5, max = 1000)
    @Config.Comment("Pause generation if a single continuation step exceeds this threshold in milliseconds.")
    public static int slowCallbackMillis = 50;

    @Mod.EventBusSubscriber(modid = ThaumicJEI.MOD_ID)
    public static class EventHandler {
        @SubscribeEvent
        public static void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent event) {
            if (event.getModID().equals(ThaumicJEI.MOD_ID)) {
                ConfigManager.sync(ThaumicJEI.MOD_ID, Config.Type.INSTANCE);
                com.buuz135.thaumicjei.event.ResearchManager.requestRefresh();
            }
        }
    }

}

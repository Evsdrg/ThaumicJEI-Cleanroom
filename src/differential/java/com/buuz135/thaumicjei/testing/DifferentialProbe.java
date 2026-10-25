package com.buuz135.thaumicjei.testing;

import com.buuz135.thaumicjei.cache.GenerationManager;
import com.buuz135.thaumicjei.data.AspectSnapshot;
import com.buuz135.thaumicjei.ingredient.CachedItemAspects;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.internal.CommonInternals;
import thaumcraft.common.lib.crafting.ThaumcraftCraftingManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Mod.EventBusSubscriber(modid = "thaumicjei", value = Side.CLIENT)
public final class DifferentialProbe {
    private static final List<String> TARGETS = List.of(
            "minecraft:apple",
            "retro_sophisticated_backpacks:backpack_leather",
            "retro_sophisticated_backpacks:backpack_iron",
            "retro_sophisticated_backpacks:backpack_gold",
            "retro_sophisticated_backpacks:backpack_diamond",
            "retro_sophisticated_backpacks:backpack_obsidian",
            "thaumicenergistics:arcane_assembler",
            "thaumcraft:thaumonomicon",
            "appliedenergistics2:facade",
            "buildcraftsilicon:plug_facade");
    private static boolean finished;
    private static int ticks;

    private DifferentialProbe() {}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END || !Boolean.getBoolean("thaumicjei.differential")) return;
        if (++ticks < 20) return;
        ticks = 0;
        if (!GenerationManager.status().startsWith("ready")) return;
        finished = true;
        var configured = System.getProperty("thaumicjei.differential.output");
        var output = configured == null ? Loader.instance().getConfigDir().toPath().resolve("thaumicjei-differential.txt")
                : Path.of(configured);
        try { verify(output); }
        catch (Throwable throwable) {
            try { Files.writeString(output, "VERIFY_FAILURE " + throwable + "\n", StandardCharsets.UTF_8); }
            catch (Exception exception) { exception.printStackTrace(); }
        }
    }

    @SuppressWarnings("unchecked")
    private static void verify(Path output) throws Exception {
        var field = GenerationManager.class.getDeclaredField("seeds"); field.setAccessible(true);
        var seeds = (List<ItemStack>) field.get(null);
        var backup = new HashMap<Integer, AspectList>();
        CommonInternals.objectTags.forEach((key, value) -> backup.put(key, value.copy()));
        var snapshot = CachedItemAspects.view().snapshot();
        var lines = new ArrayList<String>();
        int compared = 0, mismatches = 0, missing = 0;
        try {
            for (var target : TARGETS) {
                ItemStack stack = null;
                for (var seed : seeds) if (target.equals(String.valueOf(seed.getItem().getRegistryName()))) { stack = seed; break; }
                if (stack == null) { lines.add("MISSING " + target); missing++; continue; }
                if (!(snapshot.lookup(stack) instanceof AspectSnapshot.Known(var amounts))) {
                    lines.add("UNKNOWN " + target); mismatches++; continue;
                }
                var expected = new TreeMap<String, Integer>();
                for (var amount : amounts) expected.put(amount.aspect().getTag(), amount.amount());
                long started = System.nanoTime();
                var actual = values(ThaumcraftCraftingManager.getObjectTags(stack.copy()));
                long elapsed = (System.nanoTime() - started) / 1_000_000;
                boolean equal = expected.equals(actual);
                if (!equal) mismatches++;
                compared++;
                lines.add((equal ? "MATCH " : "MISMATCH ") + target + " originalMs=" + elapsed
                        + " expected=" + expected + " original=" + actual);
            }
        } finally {
            CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(backup);
        }
        lines.add("SUMMARY compared=" + compared + " missing=" + missing + " mismatches=" + mismatches
                + " result=" + (compared == TARGETS.size() && missing == 0 && mismatches == 0 ? "PASS" : "FAIL"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static Map<String, Integer> values(AspectList list) {
        var result = new TreeMap<String, Integer>();
        if (list != null) for (var aspect : list.getAspects()) if (aspect != null)
            result.put(aspect.getTag(), list.getAmount(aspect));
        return result;
    }
}

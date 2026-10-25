package com.buuz135.thaumicjei.cache;

import com.buuz135.thaumicjei.ThaumcraftJEIPlugin;
import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.recipe.OrderedRecipeIndex;
import mezz.jei.api.IJeiRuntime;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.List;
import java.util.concurrent.Executors;

@Mod.EventBusSubscriber(modid = ThaumicJEI.MOD_ID, value = Side.CLIENT)
public final class GenerationManager {
    private static final CacheGeneration GENERATION = new CacheGeneration(
            Executors.newSingleThreadExecutor(runnable -> {
                var thread = new Thread(runnable, "ThaumicJEI Cache IO");
                thread.setDaemon(true);
                return thread;
            }),
            OrderedRecipeIndex::capture,
            () -> ThaumcraftJEIPlugin.runtime,
            () -> Loader.instance().getConfigDir().toPath().resolve("thaumicjei-cache-v2.json"));

    private GenerationManager() {}

    public static void prepare(OrderedRecipeIndex captured, List<ItemStack> ingredients) {
        GENERATION.prepare(captured, ingredients);
    }

    public static void attach(IJeiRuntime runtime) {
        GENERATION.attach(runtime);
    }

    public static GenerationStatus status() {
        return GENERATION.status();
    }

    public static void pause() {
        GENERATION.pause();
    }

    public static void resume() {
        GENERATION.resume();
    }

    public static void cancel() {
        GENERATION.cancel();
    }

    public static void rebuild() {
        GENERATION.rebuild();
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) GENERATION.tick();
    }
}

package com.buuz135.thaumicjei.gui;

import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.config.ThaumicConfig;
import com.buuz135.thaumicjei.ingredient.CachedItemAspects;
import com.buuz135.thaumicjei.util.AspectUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import thaumcraft.common.config.ModConfig;

import java.util.List;

@Mod.EventBusSubscriber(modid = ThaumicJEI.MOD_ID, value = Side.CLIENT)
public final class AspectTooltipHandler {
    private static final String MARKER = "\u00a7r\u00a7r\u00a7r";
    private record TooltipSnapshot(long generation, net.minecraft.item.ItemStack stack,
                                   List<com.buuz135.thaumicjei.data.AspectSnapshot.Amount> amounts) {
    }
    private static TooltipSnapshot pending;

    private static boolean enabled(Minecraft minecraft) {
        return ThaumicConfig.forceAspectTooltipInAllGUI && minecraft.currentScreen != null
                && !(minecraft.currentScreen instanceof GuiContainer)
                && GuiScreen.isShiftKeyDown() != ModConfig.CONFIG_GRAPHICS.showTags;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tooltipEvent(ItemTooltipEvent event) {
        pending = null;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!enabled(minecraft)) return;
        var view = CachedItemAspects.view();
        var amounts = switch (view.snapshot().lookup(event.getItemStack())) {
            case com.buuz135.thaumicjei.data.AspectSnapshot.Known(var values) -> values;
            case com.buuz135.thaumicjei.data.AspectSnapshot.Unknown() -> List.<com.buuz135.thaumicjei.data.AspectSnapshot.Amount>of();
        };
        if (amounts.isEmpty()) return;
        pending = new TooltipSnapshot(view.generation(), event.getItemStack(), amounts);
        int spaces = Math.min(120, (int) Math.ceil(amounts.size() * 19.0
                / Math.max(1, minecraft.fontRenderer.getStringWidth(" "))));
        String line = MARKER + " ".repeat(spaces);
        int rows = (int) Math.ceil(18.0 / minecraft.fontRenderer.FONT_HEIGHT);
        for (int i = 0; i < rows; i++) event.getToolTip().add(line);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tooltipEvent(RenderTooltipEvent.PostBackground event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!enabled(minecraft) || minecraft.inGameHasFocus || pending == null
                || pending.generation() != CachedItemAspects.view().generation()
                || pending.stack() != event.getStack()) return;
        List<String> lines = event.getLines();
        int bottom = event.getHeight();
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i) != null && lines.get(i).startsWith(MARKER)) {
                var amounts = pending.amounts();
                pending = null;
                for (int index = 0; index < amounts.size(); index++) {
                    AspectUtils.renderAspectList(amounts.get(index).asList(),
                            event.getX() + index * 19, event.getY() + bottom - 16, minecraft);
                }
                return;
            }
            bottom -= 10;
        }
    }
}

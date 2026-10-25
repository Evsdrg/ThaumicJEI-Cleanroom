package com.buuz135.thaumicjei.gui;

import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.config.ThaumicConfig;
import com.buuz135.thaumicjei.data.AspectSnapshot;
import com.buuz135.thaumicjei.ingredient.CachedItemAspects;
import com.buuz135.thaumicjei.util.AspectUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
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
    private static final String ASPECT_ROW_MARKER = "\u00a7r\u00a7r\u00a7r";
    private static final int ASPECT_SPACING = 19;
    private static final int ASPECT_HEIGHT = 16;
    private static final int RESERVED_ROW_HEIGHT = 18;
    private static final int TOOLTIP_LINE_HEIGHT = 10;
    private static final int MAX_PADDING_SPACES = 120;

    private record TooltipSnapshot(long generation, ItemStack stack, List<AspectSnapshot.Amount> amounts) {}

    private static TooltipSnapshot pending;

    private static boolean enabled(Minecraft minecraft) {
        return ThaumicConfig.forceAspectTooltipInAllGUI && minecraft.currentScreen != null
                && !(minecraft.currentScreen instanceof GuiContainer)
                && GuiScreen.isShiftKeyDown() != ModConfig.CONFIG_GRAPHICS.showTags;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void reserveAspectRows(ItemTooltipEvent event) {
        pending = null;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!enabled(minecraft)) return;
        var view = CachedItemAspects.view();
        var amounts = switch (view.snapshot().lookup(event.getItemStack())) {
            case AspectSnapshot.Known(var values) -> values;
            case AspectSnapshot.Unknown() -> List.<AspectSnapshot.Amount>of();
        };
        if (amounts.isEmpty()) return;
        pending = new TooltipSnapshot(view.generation(), event.getItemStack(), amounts);
        int spaces = Math.min(MAX_PADDING_SPACES, (int) Math.ceil(amounts.size() * (double) ASPECT_SPACING
                / Math.max(1, minecraft.fontRenderer.getStringWidth(" "))));
        String line = ASPECT_ROW_MARKER + " ".repeat(spaces);
        int rows = (int) Math.ceil((double) RESERVED_ROW_HEIGHT / minecraft.fontRenderer.FONT_HEIGHT);
        for (int row = 0; row < rows; row++) event.getToolTip().add(line);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void renderReservedAspects(RenderTooltipEvent.PostBackground event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!enabled(minecraft) || minecraft.inGameHasFocus || pending == null
                || pending.generation() != CachedItemAspects.view().generation()
                || pending.stack() != event.getStack()) return;
        List<String> lines = event.getLines();
        int rowBottom = event.getHeight();
        for (int row = lines.size() - 1; row >= 0; row--) {
            if (lines.get(row) != null && lines.get(row).startsWith(ASPECT_ROW_MARKER)) {
                var amounts = pending.amounts();
                pending = null;
                for (int index = 0; index < amounts.size(); index++) {
                    AspectUtils.renderAspectList(amounts.get(index).asList(),
                            event.getX() + index * ASPECT_SPACING, event.getY() + rowBottom - ASPECT_HEIGHT, minecraft);
                }
                return;
            }
            rowBottom -= TOOLTIP_LINE_HEIGHT;
        }
    }
}

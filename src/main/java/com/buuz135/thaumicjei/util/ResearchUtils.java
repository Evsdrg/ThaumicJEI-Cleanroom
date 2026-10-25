package com.buuz135.thaumicjei.util;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.TextFormatting;
import thaumcraft.api.capabilities.IPlayerKnowledge;
import thaumcraft.api.capabilities.ThaumcraftCapabilities;
import thaumcraft.api.research.ResearchCategories;
import thaumcraft.api.research.ResearchEntry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ResearchUtils {

    private static final String MISSING_HEADER = TextFormatting.GOLD + "Missing research:";

    private ResearchUtils() {
    }

    /**
     * Checks the research requirements of a recipe. A null or empty requirement (or a null/empty element)
     * means "no requirement". Requirements are ANDed together; each element is split on "&&" so that a
     * single research event can satisfy the part it announced, while every other condition goes through
     * {@link ThaumcraftCapabilities#knowsResearch(EntityPlayer, String...)}, keeping Thaumcraft's own
     * "a||b" and "research@stage" semantics intact.
     *
     * @param approvedKeys lower-case research keys announced by a {@code ResearchEvent}, may be null
     */
    public static boolean knowsAllResearch(EntityPlayer player, Set<String> approvedKeys, String... research) {
        if (research == null || research.length == 0) return true;
        for (String requirement : research) {
            if (!knowsRequirement(player, approvedKeys, requirement)) return false;
        }
        return true;
    }

    private static boolean knowsRequirement(EntityPlayer player, Set<String> approvedKeys, String requirement) {
        if (requirement == null) return true;
        String trimmed = requirement.trim();
        if (trimmed.isEmpty()) return true;
        if (trimmed.indexOf("&&") < 0) return isConditionKnown(player, approvedKeys, trimmed);
        for (String part : trimmed.split("&&")) {
            if (!isConditionKnown(player, approvedKeys, part)) return false;
        }
        return true;
    }

    private static boolean isConditionKnown(EntityPlayer player, Set<String> approvedKeys, String condition) {
        String trimmed = condition.trim();
        if (trimmed.isEmpty()) return true;
        // Only an exact, non-compound condition may be pre-approved by an event key.
        if (approvedKeys != null && !approvedKeys.isEmpty() && trimmed.indexOf("||") < 0
                && approvedKeys.contains(trimmed.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return player != null && ThaumcraftCapabilities.knowsResearch(player, trimmed);
    }

    /**
     * Case-insensitive check against the raw known-research list, used to confirm an event key whose
     * casing may differ from the registered key. Does not interpret "&&", "||" or "@stage".
     */
    public static boolean knowsKeyIgnoringCase(EntityPlayer player, String key) {
        if (player == null || key == null || key.isEmpty()) return false;
        IPlayerKnowledge knowledge = ThaumcraftCapabilities.getKnowledge(player);
        if (knowledge == null) return false;
        for (String known : knowledge.getResearchList()) {
            if (known != null && known.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    public static List<String> generateMissingResearchList(String... research) {
        List<String> list = new ArrayList<>();
        list.add(MISSING_HEADER);
        if (research == null) return list;
        EntityPlayer player = Minecraft.getMinecraft().player;
        Set<String> seen = new LinkedHashSet<>();
        for (String requirement : research) {
            if (requirement == null) continue;
            String trimmed = requirement.trim();
            if (trimmed.isEmpty()) continue;
            for (String condition : trimmed.split("&&")) {
                String part = condition.trim();
                if (part.isEmpty()) continue;
                if (player != null && ThaumcraftCapabilities.knowsResearch(player, part)) continue;
                if (!seen.add(part.toLowerCase(Locale.ROOT))) continue;
                list.add("- " + TextFormatting.RED + getDisplayName(part));
            }
        }
        return list;
    }

    private static String getDisplayName(String condition) {
        String base = condition;
        String stage = null;
        int at = condition.indexOf('@');
        if (at >= 0) {
            base = condition.substring(0, at).trim();
            stage = condition.substring(at + 1).trim();
        }
        String name = base;
        if (!base.isEmpty() && base.indexOf("||") < 0) {
            ResearchEntry entry = ResearchCategories.getResearch(base);
            if (entry != null && entry.getLocalizedName() != null && !entry.getLocalizedName().isEmpty()) {
                name = entry.getLocalizedName();
            }
        }
        if (stage != null && !stage.isEmpty()) {
            return name + TextFormatting.GRAY + " (Stage " + stage + ")";
        }
        return name;
    }
}

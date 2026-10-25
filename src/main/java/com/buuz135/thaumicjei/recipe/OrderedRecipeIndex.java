package com.buuz135.thaumicjei.recipe;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.oredict.OreDictionary;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.crafting.CrucibleRecipe;
import thaumcraft.api.crafting.IArcaneRecipe;
import thaumcraft.api.crafting.IThaumcraftRecipe;
import thaumcraft.api.crafting.InfusionRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OrderedRecipeIndex {
    private record OutputKey(Item item, int metadata) {}
    private record Entry<T>(String id, T recipe) {}

    public record Candidate<T>(int ordinal, T recipe, ItemStack output, String id) {
        public Candidate {
            output = output.copy();
            if (id == null || id.isBlank()) id = fallbackId(ordinal, recipe);
        }

        public Candidate(int ordinal, T recipe, ItemStack output) {
            this(ordinal, recipe, output, null);
        }

        @Override
        public ItemStack output() {
            return output.copy();
        }

        public int outputCount() {
            return output.getCount();
        }
    }

    private final Map<OutputKey, List<Candidate<IRecipe>>> craftingOutputs;
    private final Map<OutputKey, List<Candidate<CrucibleRecipe>>> crucibleOutputs;
    private final Map<OutputKey, List<Candidate<InfusionRecipe>>> infusionOutputs;
    private final List<IArcaneRecipe> arcaneRecipes;
    private final List<CrucibleRecipe> crucibleRecipes;
    private final List<InfusionRecipe> infusionRecipes;
    private final int unsupportedCount;

    private OrderedRecipeIndex(Map<OutputKey, List<Candidate<IRecipe>>> craftingOutputs,
                               Map<OutputKey, List<Candidate<CrucibleRecipe>>> crucibleOutputs,
                               Map<OutputKey, List<Candidate<InfusionRecipe>>> infusionOutputs,
                               List<IArcaneRecipe> arcaneRecipes, List<CrucibleRecipe> crucibleRecipes,
                               List<InfusionRecipe> infusionRecipes, int unsupportedCount) {
        this.craftingOutputs = freeze(craftingOutputs);
        this.crucibleOutputs = freeze(crucibleOutputs);
        this.infusionOutputs = freeze(infusionOutputs);
        this.arcaneRecipes = List.copyOf(arcaneRecipes);
        this.crucibleRecipes = List.copyOf(crucibleRecipes);
        this.infusionRecipes = List.copyOf(infusionRecipes);
        this.unsupportedCount = unsupportedCount;
    }

    private static <T> Map<OutputKey, List<Candidate<T>>> freeze(Map<OutputKey, List<Candidate<T>>> outputs) {
        var frozen = new HashMap<OutputKey, List<Candidate<T>>>();
        outputs.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        return Map.copyOf(frozen);
    }

    public static OrderedRecipeIndex capture() {
        var crafting = new ArrayList<Entry<IRecipe>>();
        for (var key : CraftingManager.REGISTRY.getKeys()) {
            crafting.add(new Entry<>(key.toString(), CraftingManager.REGISTRY.getObject(key)));
        }
        var thaumic = new ArrayList<Entry<IThaumcraftRecipe>>();
        for (var entry : ThaumcraftApi.getCraftingRecipes().entrySet()) {
            thaumic.add(new Entry<>(entry.getKey().toString(), entry.getValue()));
        }
        return fromEntries(crafting, thaumic);
    }

    static OrderedRecipeIndex from(List<IRecipe> crafting, List<IThaumcraftRecipe> thaumic) {
        var craftingEntries = new ArrayList<Entry<IRecipe>>();
        for (int i = 0; i < crafting.size(); i++) {
            craftingEntries.add(new Entry<>(fallbackId(i, crafting.get(i)), crafting.get(i)));
        }
        var thaumicEntries = new ArrayList<Entry<IThaumcraftRecipe>>();
        for (int i = 0; i < thaumic.size(); i++) {
            thaumicEntries.add(new Entry<>(fallbackId(i, thaumic.get(i)), thaumic.get(i)));
        }
        return fromEntries(craftingEntries, thaumicEntries);
    }

    private static OrderedRecipeIndex fromEntries(List<Entry<IRecipe>> crafting, List<Entry<IThaumcraftRecipe>> thaumic) {
        var craftingOutputs = new HashMap<OutputKey, List<Candidate<IRecipe>>>();
        var crucibleOutputs = new HashMap<OutputKey, List<Candidate<CrucibleRecipe>>>();
        var infusionOutputs = new HashMap<OutputKey, List<Candidate<InfusionRecipe>>>();
        var arcaneRecipes = new ArrayList<IArcaneRecipe>();
        var crucibleRecipes = new ArrayList<CrucibleRecipe>();
        var infusionRecipes = new ArrayList<InfusionRecipe>();
        int unsupported = 0;
        int ordinal = 0;
        for (var entry : crafting) {
            var recipe = entry.recipe();
            if (recipe instanceof IArcaneRecipe arcane) arcaneRecipes.add(arcane);
            try {
                if (recipe != null) add(craftingOutputs, recipe.getRecipeOutput(), true, ordinal, recipe, entry.id());
            } catch (RuntimeException | LinkageError exception) {
                unsupported++;
            }
            ordinal++;
        }
        ordinal = 0;
        for (var entry : thaumic) {
            try {
                switch (entry.recipe()) {
                    case CrucibleRecipe recipe -> {
                        crucibleRecipes.add(recipe);
                        add(crucibleOutputs, recipe.getRecipeOutput(), false, ordinal, recipe, entry.id());
                    }
                    case InfusionRecipe recipe -> {
                        if (recipe.getRecipeInput() != null && recipe.recipeOutput != null) infusionRecipes.add(recipe);
                        if (recipe.getRecipeOutput() instanceof ItemStack output) {
                            add(infusionOutputs, output, false, ordinal, recipe, entry.id());
                        }
                    }
                    case null -> { }
                    default -> { }
                }
            } catch (RuntimeException | LinkageError exception) {
                unsupported++;
            }
            ordinal++;
        }
        return new OrderedRecipeIndex(craftingOutputs, crucibleOutputs, infusionOutputs,
                arcaneRecipes, crucibleRecipes, infusionRecipes, unsupported);
    }

    private static String fallbackId(int ordinal, Object recipe) {
        if (recipe instanceof IRecipe value && value.getRegistryName() != null) return value.getRegistryName().toString();
        return recipe == null ? "null@" + ordinal : recipe.getClass().getName() + "@" + ordinal;
    }

    private static <T> void add(Map<OutputKey, List<Candidate<T>>> outputs, ItemStack output,
                                boolean normalizeWildcard, int ordinal, T recipe, String id) {
        if (output == null || output.getItem() == null || Item.getIdFromItem(output.getItem()) <= 0) return;
        outputs.computeIfAbsent(key(output, normalizeWildcard), unused -> new ArrayList<>())
                .add(new Candidate<>(ordinal, recipe, output, id));
    }

    private static OutputKey key(ItemStack stack, boolean normalizeWildcard) {
        int metadata = stack.getMetadata();
        if (normalizeWildcard && metadata == OreDictionary.WILDCARD_VALUE) metadata = 0;
        return new OutputKey(stack.getItem(), metadata);
    }

    public List<Candidate<IRecipe>> craftingCandidates(ItemStack output) {
        return output.isEmpty() ? List.of() : craftingOutputs.getOrDefault(key(output, true), List.of());
    }

    public List<Candidate<CrucibleRecipe>> crucibleCandidates(ItemStack output) {
        return output.isEmpty() ? List.of() : crucibleOutputs.getOrDefault(key(output, false), List.of());
    }

    public List<Candidate<InfusionRecipe>> infusionCandidates(ItemStack output) {
        return output.isEmpty() ? List.of() : infusionOutputs.getOrDefault(key(output, false), List.of());
    }

    public List<IArcaneRecipe> arcaneRecipes() {
        return arcaneRecipes;
    }

    public List<CrucibleRecipe> crucibleRecipes() {
        return crucibleRecipes;
    }

    public List<InfusionRecipe> infusionRecipes() {
        return infusionRecipes;
    }

    public int unsupportedCount() {
        return unsupportedCount;
    }
}

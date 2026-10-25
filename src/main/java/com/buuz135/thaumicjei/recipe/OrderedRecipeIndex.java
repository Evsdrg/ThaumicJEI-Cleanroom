package com.buuz135.thaumicjei.recipe;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
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
    public enum Kind { CRUCIBLE, INFUSION, CRAFTING }
    private record OutputKey(Item item, int metadata, Kind kind) {}
    private record Entry<T>(String id, T recipe) {}

    public record Candidate(int ordinal, Object recipe, ItemStack output, String id) {
        public Candidate {
            output = output.copy();
            if (id == null || id.isBlank()) id = fallbackId(ordinal, recipe);
        }
        public Candidate(int ordinal, Object recipe, ItemStack output) { this(ordinal, recipe, output, null); }
        @Override public ItemStack output() { return output.copy(); }
        public int outputCount() { return output.getCount(); }
    }

    private final Map<OutputKey, List<Candidate>> outputs;
    private final List<IArcaneRecipe> arcane;
    private final List<CrucibleRecipe> crucible;
    private final List<InfusionRecipe> infusion;
    private final int unsupportedCount;

    private OrderedRecipeIndex(Map<OutputKey, List<Candidate>> outputs, List<IArcaneRecipe> arcane,
                               List<CrucibleRecipe> crucible, List<InfusionRecipe> infusion, int unsupportedCount) {
        var frozen = new HashMap<OutputKey, List<Candidate>>();
        outputs.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        this.outputs = Map.copyOf(frozen);
        this.arcane = List.copyOf(arcane);
        this.crucible = List.copyOf(crucible);
        this.infusion = List.copyOf(infusion);
        this.unsupportedCount = unsupportedCount;
    }

    public static OrderedRecipeIndex capture() {
        var crafting = new ArrayList<Entry<IRecipe>>();
        for (var key : CraftingManager.REGISTRY.getKeys())
            crafting.add(new Entry<>(key.toString(), CraftingManager.REGISTRY.getObject(key)));
        var thaumic = new ArrayList<Entry<IThaumcraftRecipe>>();
        for (var entry : ThaumcraftApi.getCraftingRecipes().entrySet())
            thaumic.add(new Entry<>(entry.getKey().toString(), entry.getValue()));
        return fromEntries(crafting, thaumic);
    }

    static OrderedRecipeIndex from(List<IRecipe> crafting, List<IThaumcraftRecipe> thaumic) {
        var craftingEntries = new ArrayList<Entry<IRecipe>>();
        for (int i = 0; i < crafting.size(); i++) craftingEntries.add(new Entry<>(fallbackId(i, crafting.get(i)), crafting.get(i)));
        var thaumicEntries = new ArrayList<Entry<IThaumcraftRecipe>>();
        for (int i = 0; i < thaumic.size(); i++) thaumicEntries.add(new Entry<>(fallbackId(i, thaumic.get(i)), thaumic.get(i)));
        return fromEntries(craftingEntries, thaumicEntries);
    }

    private static OrderedRecipeIndex fromEntries(List<Entry<IRecipe>> crafting, List<Entry<IThaumcraftRecipe>> thaumic) {
        var outputs = new HashMap<OutputKey, List<Candidate>>();
        var arcane = new ArrayList<IArcaneRecipe>();
        var crucible = new ArrayList<CrucibleRecipe>();
        var infusion = new ArrayList<InfusionRecipe>();
        int unsupported = 0, ordinal = 0;
        for (var entry : crafting) {
            var recipe = entry.recipe();
            if (recipe instanceof IArcaneRecipe value) arcane.add(value);
            try {
                if (recipe != null) add(outputs, recipe.getRecipeOutput(), Kind.CRAFTING, ordinal, recipe, entry.id());
            } catch (RuntimeException | LinkageError exception) { unsupported++; }
            ordinal++;
        }
        ordinal = 0;
        for (var entry : thaumic) {
            var recipe = entry.recipe();
            try {
                switch (recipe) {
                    case CrucibleRecipe value -> {
                        crucible.add(value);
                        add(outputs, value.getRecipeOutput(), Kind.CRUCIBLE, ordinal, value, entry.id());
                    }
                    case InfusionRecipe value -> {
                        if (value.getRecipeInput() != null && value.recipeOutput != null) infusion.add(value);
                        if (value.getRecipeOutput() instanceof ItemStack output)
                            add(outputs, output, Kind.INFUSION, ordinal, value, entry.id());
                    }
                    case null -> { }
                    default -> { }
                }
            } catch (RuntimeException | LinkageError exception) { unsupported++; }
            ordinal++;
        }
        return new OrderedRecipeIndex(outputs, arcane, crucible, infusion, unsupported);
    }

    private static String fallbackId(int ordinal, Object recipe) {
        if (recipe instanceof IRecipe value && value.getRegistryName() != null) return value.getRegistryName().toString();
        return recipe == null ? "null@" + ordinal : recipe.getClass().getName() + "@" + ordinal;
    }

    private static void add(Map<OutputKey, List<Candidate>> outputs, ItemStack output,
                            Kind kind, int ordinal, Object recipe, String id) {
        if (output == null || output.getItem() == null || Item.getIdFromItem(output.getItem()) <= 0) return;
        var key = key(output, kind);
        outputs.computeIfAbsent(key, unused -> new ArrayList<>()).add(new Candidate(ordinal, recipe, output, id));
    }

    private static OutputKey key(ItemStack stack, Kind kind) {
        int metadata = stack.getMetadata();
        if (kind == Kind.CRAFTING && metadata == 32767) metadata = 0;
        return new OutputKey(stack.getItem(), metadata, kind);
    }

    public List<Candidate> candidates(ItemStack output, Kind kind) {
        return output.isEmpty() ? List.of() : outputs.getOrDefault(key(output, kind), List.of());
    }
    public List<Candidate> allCandidates() {
        return outputs.values().stream().flatMap(List::stream)
                .sorted(java.util.Comparator.comparing((Candidate value) -> value.recipe() instanceof IRecipe ? 1 : 0)
                        .thenComparingInt(Candidate::ordinal)).toList();
    }
    public List<IArcaneRecipe> arcaneRecipes() { return arcane; }
    public List<CrucibleRecipe> crucibleRecipes() { return crucible; }
    public List<InfusionRecipe> infusionRecipes() { return infusion; }
    public int unsupportedCount() { return unsupportedCount; }
}

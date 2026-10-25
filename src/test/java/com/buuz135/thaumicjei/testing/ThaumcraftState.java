package com.buuz135.thaumicjei.testing;

import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistryModifiable;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.IThaumcraftRecipe;
import thaumcraft.api.internal.CommonInternals;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ThaumcraftState implements AutoCloseable {
    private final Map<Integer, AspectList> objectTags = copyObjectTags();
    private final Map<ResourceLocation, IThaumcraftRecipe> recipes = new HashMap<>(ThaumcraftApi.getCraftingRecipes());
    private final Set<ResourceLocation> craftingRecipeIds = new HashSet<>(ForgeRegistries.RECIPES.getKeys());

    public static ThaumcraftState cleared() {
        var state = new ThaumcraftState();
        CommonInternals.objectTags.clear();
        return state;
    }

    public Map<Integer, AspectList> checkpointTags() {
        return copyObjectTags();
    }

    public void restoreTags(Map<Integer, AspectList> checkpoint) {
        CommonInternals.objectTags.clear();
        checkpoint.forEach((key, value) -> CommonInternals.objectTags.put(key, value.copy()));
    }

    private static Map<Integer, AspectList> copyObjectTags() {
        var copy = new HashMap<Integer, AspectList>();
        CommonInternals.objectTags.forEach((key, value) -> copy.put(key, value.copy()));
        return copy;
    }

    @Override
    public void close() {
        restoreTags(objectTags);
        ThaumcraftApi.getCraftingRecipes().clear();
        ThaumcraftApi.getCraftingRecipes().putAll(recipes);
        if (!(ForgeRegistries.RECIPES instanceof IForgeRegistryModifiable<IRecipe> registry)) {
            throw new IllegalStateException("Test recipe registry does not support cleanup");
        }
        for (var key : new HashSet<>(registry.getKeys())) {
            if (!craftingRecipeIds.contains(key)) registry.remove(key);
        }
    }
}

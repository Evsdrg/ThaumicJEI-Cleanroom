/*
 * This file is part of Hot or Not.
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
package com.buuz135.thaumicjei;

import com.buuz135.thaumicjei.category.*;
import com.buuz135.thaumicjei.config.ThaumicConfig;
import com.buuz135.thaumicjei.gui.FocalManipulatorAdvancedGuiHandler;
import com.buuz135.thaumicjei.gui.ResearchTableAdvancedGuiHandler;
import com.buuz135.thaumicjei.ingredient.AspectIngredientFactory;
import com.buuz135.thaumicjei.ingredient.AspectIngredientRender;
import com.buuz135.thaumicjei.ingredient.AspectListIngredientHelper;
import com.buuz135.thaumicjei.event.ResearchManager;
import mezz.jei.api.*;
import mezz.jei.api.ingredients.IModIngredientRegistration;
import mezz.jei.api.ingredients.VanillaTypes;
import mezz.jei.api.recipe.IIngredientType;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import mezz.jei.api.recipe.IRecipeWrapper;
import mezz.jei.api.recipe.VanillaRecipeCategoryUid;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import thaumcraft.api.ThaumcraftApi;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.CrucibleRecipe;
import thaumcraft.api.crafting.IArcaneRecipe;
import thaumcraft.api.crafting.IThaumcraftRecipe;
import thaumcraft.api.crafting.InfusionRecipe;
import thaumcraft.client.gui.GuiArcaneWorkbench;
import thaumcraft.common.container.ContainerArcaneWorkbench;

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

@JEIPlugin
public class ThaumcraftJEIPlugin implements IModPlugin {

    public static final IIngredientType<AspectList> ASPECT_LIST = () -> AspectList.class;

    public static ArcaneWorkbenchCategory arcaneWorkbenchCategory;
    public static CrucibleCategory crucibleCategory;
    public static InfusionCategory infusionCategory;
    public static AspectFromItemStackCategory aspectFromItemStackCategory;
    public static AspectCompoundCategory aspectCompoundCategory;
    public static IJeiRuntime runtime;
    public static HashMap<IRecipeWrapper, String> recipes = new HashMap<>();
    public static volatile com.buuz135.thaumicjei.recipe.OrderedRecipeIndex recipeIndex;

    @Override
    public void registerItemSubtypes(ISubtypeRegistry subtypeRegistry) {
        subtypeRegistry.useNbtForSubtypes(Item.getByNameOrId("thaumcraft:crystal_essence"));
        subtypeRegistry.useNbtForSubtypes(Item.getByNameOrId("thaumcraft:phial"));
    }

    @Override
    public void registerIngredients(IModIngredientRegistration registry) {
        registry.register(ASPECT_LIST, AspectIngredientFactory.create(), new AspectListIngredientHelper(), new AspectIngredientRender());
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registry) {
        arcaneWorkbenchCategory = new ArcaneWorkbenchCategory(registry.getJeiHelpers().getGuiHelper());
        crucibleCategory = new CrucibleCategory(registry.getJeiHelpers().getGuiHelper());
        infusionCategory = new InfusionCategory();
        aspectFromItemStackCategory = new AspectFromItemStackCategory();
        aspectCompoundCategory = new AspectCompoundCategory(registry.getJeiHelpers().getGuiHelper());
        registry.addRecipeCategories(arcaneWorkbenchCategory, crucibleCategory, infusionCategory, aspectCompoundCategory, aspectFromItemStackCategory);
    }

    @Override
    public void register(IModRegistry registry) {
        registry.addRecipeCatalyst(new ItemStack(Block.getBlockFromName(new ResourceLocation("thaumcraft", "arcane_workbench").toString())), arcaneWorkbenchCategory.getUid());
        registry.addRecipeClickArea(GuiArcaneWorkbench.class, 108, 56, 32, 32, arcaneWorkbenchCategory.getUid());
        registry.getRecipeTransferRegistry().addRecipeTransferHandler(ContainerArcaneWorkbench.class, arcaneWorkbenchCategory.getUid(), 1, 9, 16, 36);

        registry.addRecipeCatalyst(new ItemStack(Block.getBlockFromName(new ResourceLocation("thaumcraft", "crucible").toString())), crucibleCategory.getUid());

        registry.addRecipeCatalyst(new ItemStack(Block.getBlockFromName(new ResourceLocation("thaumcraft", "infusion_matrix").toString())), infusionCategory.getUid());

        var index = com.buuz135.thaumicjei.recipe.OrderedRecipeIndex.capture();
        recipeIndex = index;
        registry.addRecipes(index.arcaneRecipes().stream()
                .map(ArcaneWorkbenchCategory.ArcaneWorkbenchWrapper::new).toList(), arcaneWorkbenchCategory.getUid());
        registry.addRecipes(index.crucibleRecipes().stream()
                .map(CrucibleCategory.CrucibleWrapper::new).toList(), crucibleCategory.getUid());
        registry.addRecipes(index.infusionRecipes().stream()
                .map(InfusionCategory.InfusionWrapper::new).toList(), infusionCategory.getUid());
        ThaumicJEI.LOGGER.info("Captured ordered recipe index; {} output callbacks were unavailable.", index.unsupportedCount());

        registry.addRecipeCatalyst(new ItemStack(Item.getByNameOrId(new ResourceLocation("thaumcraft", "thaumonomicon").toString())), aspectFromItemStackCategory.getUid());

        com.buuz135.thaumicjei.cache.GenerationManager.prepare(index,
                new ArrayList<>(registry.getIngredientRegistry().getAllIngredients(VanillaTypes.ITEM)));

        registry.addRecipeCatalyst(new ItemStack(Item.getByNameOrId(new ResourceLocation("thaumcraft", "thaumonomicon").toString())), aspectCompoundCategory.getUid());
        List<AspectCompoundCategory.AspectCompoundWrapper> compoundWrappers = new ArrayList<>();
        for (Aspect aspect : Aspect.getCompoundAspects()) {
            compoundWrappers.add(new AspectCompoundCategory.AspectCompoundWrapper(aspect));
        }
        registry.addRecipes(compoundWrappers, aspectCompoundCategory.getUid());

        registry.addIngredientInfo(Arrays.asList(new ItemStack(Item.getByNameOrId(new ResourceLocation("thaumcraft", "salis_mundus").toString())),
                new ItemStack(Item.getByNameOrId(new ResourceLocation("thaumcraft", "crystal_essence").toString()))), VanillaTypes.ITEM, "To create Salis Mundis, take 3 Vis Crystals of different types and combine them with Redstone Dust by crafting them with a flint and a bowl.");
        registry.addIngredientInfo(new ItemStack(Item.getByNameOrId(new ResourceLocation("thaumcraft", "triple_meat_treat").toString())), ItemStack.class, "To create the Triple Meat Treat, take 3 different kinds of meat nuggets (produced by cooking meat in the Infernal Furnace) and mix them with sugar.");

        registry.addAdvancedGuiHandlers(new ResearchTableAdvancedGuiHandler());
        registry.addAdvancedGuiHandlers(new FocalManipulatorAdvancedGuiHandler());
    }

    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;

        recipeIndex.arcaneRecipes().forEach(recipe -> {
            var wrapper = jeiRuntime.getRecipeRegistry().getRecipeWrapper(recipe, VanillaRecipeCategoryUid.CRAFTING);
            if (wrapper != null) jeiRuntime.getRecipeRegistry().hideRecipe(wrapper);
        });

        recipes.clear();
        for (String uuid : new String[]{arcaneWorkbenchCategory.getUid(), crucibleCategory.getUid(), infusionCategory.getUid()}) {
            jeiRuntime.getRecipeRegistry().getRecipeWrappers(jeiRuntime.getRecipeRegistry().getRecipeCategory(uuid)).forEach(o -> {
                recipes.put((IRecipeWrapper) o, uuid);
                if (ThaumicConfig.hideRecipesIfMissingResearch)
                    jeiRuntime.getRecipeRegistry().hideRecipe((IRecipeWrapper) o, uuid);
            });
        }
        ResearchManager.requestRefresh();
        com.buuz135.thaumicjei.cache.GenerationManager.attach(jeiRuntime);
    }

}

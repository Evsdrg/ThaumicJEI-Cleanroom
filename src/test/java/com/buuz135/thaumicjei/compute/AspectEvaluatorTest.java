package com.buuz135.thaumicjei.compute;

import com.buuz135.thaumicjei.recipe.OrderedRecipeIndex;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.internal.CommonInternals;
import thaumcraft.common.lib.crafting.ThaumcraftCraftingManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AspectEvaluatorTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }
    private static final class Recipe extends net.minecraftforge.registries.IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {
        final ItemStack output;
        final NonNullList<Ingredient> ingredients;
        ItemStack remaining;
        boolean directOutput;
        int ingredientCalls, remainingCalls;
        Recipe(ItemStack output, ItemStack... inputs) {
            this.output = output;
            ingredients = NonNullList.create();
            for (var input : inputs) ingredients.add(Ingredient.fromStacks(input));
        }
        @Override public boolean matches(InventoryCrafting inv, World world) { return true; }
        @Override public ItemStack getCraftingResult(InventoryCrafting inv) { return output.copy(); }
        @Override public ItemStack getRecipeOutput() { return directOutput ? output : output.copy(); }
        @Override public boolean canFit(int w, int h) { return true; }
        @Override public NonNullList<Ingredient> getIngredients() { ingredientCalls++; return ingredients; }
        @Override public NonNullList<ItemStack> getRemainingItems(InventoryCrafting inventory) {
            remainingCalls++;
            if (remaining == null) return IRecipe.super.getRemainingItems(inventory);
            var result = NonNullList.<ItemStack>create(); result.add(remaining); return result;
        }
    }
    private static AspectList evaluate(AspectEvaluator evaluator, ItemStack stack, int slice) {
        var session = evaluator.session(stack);
        for (int i = 0; i < 20_000; i++) {
            switch (session.advance(slice, Long.MAX_VALUE)) {
                case AspectEvaluator.Complete(var value) -> { return value; }
                case AspectEvaluator.Failed(var reason) -> { fail(reason); }
                case AspectEvaluator.Running(var steps) -> { }
            }
        }
        throw new AssertionError("Evaluation did not finish");
    }
    private static int boundedSteps(AspectEvaluator.Session session, int maximum) {
        int steps = 0;
        while (steps < maximum) {
            switch (session.advance(1, Long.MAX_VALUE)) {
                case AspectEvaluator.Complete(var value) -> { return steps + 1; }
                case AspectEvaluator.Failed(var reason) -> { fail(reason); }
                case AspectEvaluator.Running(var progress) -> steps = progress;
            }
        }
        fail("Evaluation exceeded its step bound: " + session.progress());
        return -1;
    }

    private static Map<String, Integer> values(AspectList aspects) {
        var result = new HashMap<String, Integer>();
        if (aspects != null) for (var aspect : aspects.getAspects()) result.put(aspect.getTag(), aspects.getAmount(aspect));
        return result;
    }
    @Test
    void craftingMatchesOriginalAndPauseDoesNotChangeResult() {
        var original = new HashMap<>(CommonInternals.objectTags);
        try {
            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 13));
            var recipe = new Recipe(new ItemStack(Items.CARROT, 2), new ItemStack(Items.APPLE));
            recipe.setRegistryName(new ResourceLocation("thaumicjeitest", "carrot"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(recipe);
            var index = OrderedRecipeIndex.capture();
            var seed = new HashMap<>(CommonInternals.objectTags);
            var expected = ThaumcraftCraftingManager.getObjectTags(new ItemStack(Items.CARROT));
            for (int slice : new int[]{1, 2, 100}) {
                CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(seed);
                var actual = evaluate(new AspectEvaluator(index), new ItemStack(Items.CARROT), slice);
                assertEquals(values(expected), values(actual));
                assertEquals(seed.keySet(), CommonInternals.objectTags.keySet(), "Private evaluator must not mutate TC cache");
            }
        } finally { CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original); }
    }
    @Test
    void crucibleAndInfusionMatchOriginalRoundingAndPriority() {
        var original = new HashMap<>(CommonInternals.objectTags);
        var catalog = new HashMap<>(thaumcraft.api.ThaumcraftApi.getCraftingRecipes());
        try {
            for (boolean crucible : new boolean[]{true, false}) {
                CommonInternals.objectTags.clear(); thaumcraft.api.ThaumcraftApi.getCraftingRecipes().clear();
                thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 13));
                var output = new ItemStack(Items.BEETROOT, 2);
                if (crucible) thaumcraft.api.ThaumcraftApi.addCrucibleRecipe(new ResourceLocation("test", "crucible"),
                        new thaumcraft.api.crafting.CrucibleRecipe("", output, new ItemStack(Items.APPLE), new AspectList().add(Aspect.FIRE, 25)));
                else thaumcraft.api.ThaumcraftApi.addInfusionCraftingRecipe(new ResourceLocation("test", "infusion"),
                        new thaumcraft.api.crafting.InfusionRecipe("", output, 0, new AspectList().add(Aspect.FIRE, 25), new ItemStack(Items.APPLE)));
                var seed = new HashMap<>(CommonInternals.objectTags);
                var index = OrderedRecipeIndex.capture();
                var expected = ThaumcraftCraftingManager.getObjectTags(output);
                CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(seed);
                assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), output, 1)));
            }
        } finally {
            CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original);
            thaumcraft.api.ThaumcraftApi.getCraftingRecipes().clear(); thaumcraft.api.ThaumcraftApi.getCraftingRecipes().putAll(catalog);
        }
    }

    @Test
    void multipleCandidatesAndInputAlternativeOrderMatchOriginal() {
        var original = new HashMap<>(CommonInternals.objectTags);
        try {
            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.CARROT), new AspectList().add(Aspect.FIRE, 4));
            var output = new ItemStack(Items.BEETROOT);
            var first = new Recipe(output, new ItemStack(Items.APPLE)); first.setRegistryName(new ResourceLocation("test", "first"));
            var second = new Recipe(output, new ItemStack(Items.CARROT)); second.setRegistryName(new ResourceLocation("test", "second"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(first);
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(second);
            var expected = ThaumcraftCraftingManager.getObjectTags(output);
            for (int slice : new int[]{1, 3, 100}) assertEquals(values(expected), values(evaluate(new AspectEvaluator(OrderedRecipeIndex.capture()), output, slice)));
        } finally { CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original); }
    }

    @Test
    void failedCycleHistoryIsSharedAcrossSiblingBranches() {
        var original = new HashMap<>(CommonInternals.objectTags);
        try {
            CommonInternals.objectTags.clear();
            var output = new ItemStack(Items.CARROT);
            var intermediate = new ItemStack(Items.APPLE);
            var recipe = new Recipe(output, intermediate, intermediate);
            recipe.setRegistryName(new ResourceLocation("test", "cycle_root"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(recipe);
            var index = OrderedRecipeIndex.capture();
            var expected = values(ThaumcraftCraftingManager.getObjectTags(output));
            CommonInternals.objectTags.clear();
            var session = new AspectEvaluator(index).session(output);
            for (int step = 0; step < 9; step++)
                assertInstanceOf(AspectEvaluator.Running.class, session.advance(1, Long.MAX_VALUE));
            assertEquals(2, session.progress().historySize(),
                    "A failed child must remain in the shared history until the root finishes");
            int steps = boundedSteps(session, 64) + 9;
            var actual = switch (session.advance(1, Long.MAX_VALUE)) {
                case AspectEvaluator.Complete(var value) -> values(value);
                case AspectEvaluator.Failed(var reason) -> fail(reason);
                case AspectEvaluator.Running running -> fail("Unexpected continuation");
            };
            assertEquals(expected, actual);
            assertTrue(steps < 64, "Evaluation must complete within its continuation bound");
        } finally { CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original); }
    }

    @Test
    void failingRemainingItemSkipsOnlyItsCraftingCandidate() {
        var original = new HashMap<>(CommonInternals.objectTags);
        try {
            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            var output = new ItemStack(Items.RECORD_BLOCKS);
            var bad = new Recipe(output); bad.remaining = output;
            bad.setRegistryName(new ResourceLocation("test", "bad_remaining"));
            var good = new Recipe(output, new ItemStack(Items.APPLE));
            good.setRegistryName(new ResourceLocation("test", "good_after_bad_remaining"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(bad);
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(good);
            var index = OrderedRecipeIndex.capture();
            var seed = new HashMap<>(CommonInternals.objectTags);
            var expected = ThaumcraftCraftingManager.getObjectTags(output);
            CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(seed);
            assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), output, 1)));
        } finally { CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original); }
    }

    @Test
    void emptyCrucibleMatcherReturnsBonusOnlyInsteadOfUsingCrafting() {
        var original = new HashMap<>(CommonInternals.objectTags);
        var catalog = new HashMap<>(thaumcraft.api.ThaumcraftApi.getCraftingRecipes());
        try {
            CommonInternals.objectTags.clear(); thaumcraft.api.ThaumcraftApi.getCraftingRecipes().clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            var output = new ItemStack(Items.RECORD_CAT);
            thaumcraft.api.ThaumcraftApi.addCrucibleRecipe(new ResourceLocation("test", "empty_catalyst"),
                    new thaumcraft.api.crafting.CrucibleRecipe("", output, Ingredient.EMPTY, new AspectList().add(Aspect.FIRE, 25)));
            var crafting = new Recipe(output, new ItemStack(Items.APPLE));
            crafting.setRegistryName(new ResourceLocation("test", "must_not_replace_failed_crucible"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(crafting);
            var index = OrderedRecipeIndex.capture();
            var seed = new HashMap<>(CommonInternals.objectTags);
            var expected = ThaumcraftCraftingManager.getObjectTags(output);
            CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(seed);
            assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), output, 1)));
        } finally {
            CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original);
            thaumcraft.api.ThaumcraftApi.getCraftingRecipes().clear(); thaumcraft.api.ThaumcraftApi.getCraftingRecipes().putAll(catalog);
        }
    }

    @Test
    void oversizedIngredientListAndZeroCountOutputMatchOriginal() {
        var original = new HashMap<>(CommonInternals.objectTags);
        try {
            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            var inputs = new ItemStack[10];
            for (int i = 0; i < inputs.length; i++) inputs[i] = new ItemStack(Items.APPLE);
            var oversized = new Recipe(new ItemStack(Items.RECORD_11), inputs);
            oversized.setRegistryName(new ResourceLocation("test", "oversized_ingredients"));
            var zeroCount = new Recipe(new ItemStack(Items.RECORD_13, 0), new ItemStack(Items.APPLE));
            zeroCount.directOutput = true;
            zeroCount.setRegistryName(new ResourceLocation("test", "zero_count_output"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(oversized);
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(zeroCount);
            var index = OrderedRecipeIndex.capture();
            var seed = new HashMap<>(CommonInternals.objectTags);
            var zeroExpected = ThaumcraftCraftingManager.getObjectTags(new ItemStack(Items.RECORD_13));
            var oversizedExpected = ThaumcraftCraftingManager.getObjectTags(new ItemStack(Items.RECORD_11));
            assertEquals(Map.of(), values(zeroExpected));
            assertTrue(index.candidates(new ItemStack(Items.RECORD_13), OrderedRecipeIndex.Kind.CRAFTING).isEmpty());
            var cases = List.of(Map.entry(new ItemStack(Items.RECORD_13), zeroExpected),
                    Map.entry(new ItemStack(Items.RECORD_11), oversizedExpected));
            for (var entry : cases) {
                CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(seed);
                assertEquals(values(entry.getValue()), values(evaluate(new AspectEvaluator(index), entry.getKey(), 1)));
            }
        } finally { CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original); }
    }

    @Test
    void wildcardMetadataFallbackDoesNotMutateCachedKeys() {
        var original = new HashMap<>(CommonInternals.objectTags);
        try {
            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.RECORD_13, 1, 10), new AspectList().add(Aspect.FIRE, 7));
            var query = new ItemStack(Items.RECORD_13, 1, 32767);
            var index = OrderedRecipeIndex.capture();
            var seed = new HashMap<>(CommonInternals.objectTags);
            var expected = ThaumcraftCraftingManager.getObjectTags(query);
            CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(seed);
            assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), query, 1)));

            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.RECORD_13, 1, 15), new AspectList().add(Aspect.WATER, 9));
            query.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
            seed = new HashMap<>(CommonInternals.objectTags);
            expected = ThaumcraftCraftingManager.getObjectTags(query);
            CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(seed);
            assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), query, 1)));
        } finally { CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original); }
    }

    @Test
    void preparedPlansAreSharedAcrossDifferentNbtSessions() {
        var original = new HashMap<>(CommonInternals.objectTags);
        try {
            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            var recipe = new Recipe(new ItemStack(Items.RECORD_FAR), new ItemStack(Items.APPLE));
            recipe.setRegistryName(new ResourceLocation("test", "shared_prepared_plan"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(recipe);
            var index = OrderedRecipeIndex.capture();
            var first = new ItemStack(Items.RECORD_FAR); first.getOrCreateSubCompound("test").setInteger("variant", 1);
            var second = new ItemStack(Items.RECORD_FAR); second.getOrCreateSubCompound("test").setInteger("variant", 2);
            var seed = new HashMap<>(CommonInternals.objectTags);
            var firstExpected = ThaumcraftCraftingManager.getObjectTags(first);
            var secondExpected = ThaumcraftCraftingManager.getObjectTags(second);
            CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(seed);
            recipe.ingredientCalls = recipe.remainingCalls = 0;
            var evaluator = new AspectEvaluator(index);
            assertEquals(values(firstExpected), values(evaluate(evaluator, first, 1)));
            assertEquals(values(secondExpected), values(evaluate(evaluator, second, 1)));
            assertEquals(1, recipe.ingredientCalls);
            assertEquals(1, recipe.remainingCalls);
        } finally { CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original); }
    }

    @Test
    void knownAndEmptyValuesMatchOriginal() {
        var original = new HashMap<>(CommonInternals.objectTags);
        try {
            for (var item : new ItemStack[]{new ItemStack(Items.STICK), new ItemStack(Items.DIAMOND), ItemStack.EMPTY}) {
                CommonInternals.objectTags.clear();
                if (!item.isEmpty()) thaumcraft.api.ThaumcraftApi.registerObjectTag(item, new AspectList().add(Aspect.FIRE, 9));
                var evaluator = new AspectEvaluator(OrderedRecipeIndex.capture());
                assertEquals(values(ThaumcraftCraftingManager.getObjectTags(item)), values(evaluate(evaluator, item, 1)));
            }
        } finally { CommonInternals.objectTags.clear(); CommonInternals.objectTags.putAll(original); }
    }
}

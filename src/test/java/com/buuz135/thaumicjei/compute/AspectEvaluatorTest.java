package com.buuz135.thaumicjei.compute;

import com.buuz135.thaumicjei.recipe.OrderedRecipeIndex;
import com.buuz135.thaumicjei.testing.ThaumcraftState;
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
            var result = NonNullList.<ItemStack>create();
            result.add(remaining);
            return result;
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
        try (var state = ThaumcraftState.cleared()) {
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 13));
            var recipe = new Recipe(new ItemStack(Items.CARROT, 2), new ItemStack(Items.APPLE));
            recipe.setRegistryName(new ResourceLocation("thaumicjeitest", "carrot"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(recipe);
            var index = OrderedRecipeIndex.capture();
            var seed = state.checkpointTags();
            var expected = ThaumcraftCraftingManager.getObjectTags(new ItemStack(Items.CARROT));
            for (int slice : new int[]{1, 2, 100}) {
                state.restoreTags(seed);
                var actual = evaluate(new AspectEvaluator(index), new ItemStack(Items.CARROT), slice);
                assertEquals(values(expected), values(actual));
                assertEquals(seed.keySet(), CommonInternals.objectTags.keySet(), "Private evaluator must not mutate TC cache");
            }
        }
    }
    @Test
    void crucibleAndInfusionMatchOriginalRoundingAndPriority() {
        try (var state = ThaumcraftState.cleared()) {
            for (boolean crucible : new boolean[]{true, false}) {
                CommonInternals.objectTags.clear();
                thaumcraft.api.ThaumcraftApi.getCraftingRecipes().clear();
                thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 13));
                var output = new ItemStack(Items.BEETROOT, 2);
                if (crucible) thaumcraft.api.ThaumcraftApi.addCrucibleRecipe(new ResourceLocation("test", "crucible"),
                        new thaumcraft.api.crafting.CrucibleRecipe("", output, new ItemStack(Items.APPLE), new AspectList().add(Aspect.FIRE, 25)));
                else thaumcraft.api.ThaumcraftApi.addInfusionCraftingRecipe(new ResourceLocation("test", "infusion"),
                        new thaumcraft.api.crafting.InfusionRecipe("", output, 0, new AspectList().add(Aspect.FIRE, 25), new ItemStack(Items.APPLE)));
                var seed = state.checkpointTags();
                var index = OrderedRecipeIndex.capture();
                var expected = ThaumcraftCraftingManager.getObjectTags(output);
                state.restoreTags(seed);
                assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), output, 1)));
            }
        }
    }

    @Test
    void multipleCandidatesAndInputAlternativeOrderMatchOriginal() {
        try (var state = ThaumcraftState.cleared()) {
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.CARROT), new AspectList().add(Aspect.FIRE, 4));
            var output = new ItemStack(Items.BEETROOT);
            var first = new Recipe(output, new ItemStack(Items.APPLE)); first.setRegistryName(new ResourceLocation("test", "first"));
            var second = new Recipe(output, new ItemStack(Items.CARROT)); second.setRegistryName(new ResourceLocation("test", "second"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(first);
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(second);
            var expected = ThaumcraftCraftingManager.getObjectTags(output);
            for (int slice : new int[]{1, 3, 100}) assertEquals(values(expected), values(evaluate(new AspectEvaluator(OrderedRecipeIndex.capture()), output, slice)));
        }
    }

    @Test
    void failedCycleHistoryIsSharedAcrossSiblingBranches() {
        try (var state = ThaumcraftState.cleared()) {
            var output = new ItemStack(Items.CARROT);
            var intermediate = new ItemStack(Items.APPLE);
            var recipe = new Recipe(output, intermediate, intermediate);
            recipe.setRegistryName(new ResourceLocation("test", "cycle_root"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(recipe);
            var index = OrderedRecipeIndex.capture();
            var expected = values(ThaumcraftCraftingManager.getObjectTags(output));
            CommonInternals.objectTags.clear();
            var session = new AspectEvaluator(index).session(output);
            for (int step = 0; session.progress().historySize() < 2 && step < 64; step++) {
                assertInstanceOf(AspectEvaluator.Running.class, session.advance(1, Long.MAX_VALUE));
            }
            assertEquals(2, session.progress().historySize(),
                    "A failed child must remain in the shared history until the root finishes");
            int steps = boundedSteps(session, 64);
            var actual = switch (session.advance(1, Long.MAX_VALUE)) {
                case AspectEvaluator.Complete(var value) -> values(value);
                case AspectEvaluator.Failed(var reason) -> fail(reason);
                case AspectEvaluator.Running running -> fail("Unexpected continuation");
            };
            assertEquals(expected, actual);
            assertTrue(steps < 64, "Evaluation must complete within its continuation bound");
        }
    }

    @Test
    void failingRemainingItemSkipsOnlyItsCraftingCandidate() {
        try (var state = ThaumcraftState.cleared()) {
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            var output = new ItemStack(Items.RECORD_BLOCKS);
            var bad = new Recipe(output);
            bad.remaining = output;
            bad.setRegistryName(new ResourceLocation("test", "bad_remaining"));
            var good = new Recipe(output, new ItemStack(Items.APPLE));
            good.setRegistryName(new ResourceLocation("test", "good_after_bad_remaining"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(bad);
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(good);
            var index = OrderedRecipeIndex.capture();
            var seed = state.checkpointTags();
            var expected = ThaumcraftCraftingManager.getObjectTags(output);
            state.restoreTags(seed);
            assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), output, 1)));
        }
    }

    @Test
    void emptyCrucibleMatcherReturnsBonusOnlyInsteadOfUsingCrafting() {
        try (var state = ThaumcraftState.cleared()) {
            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.getCraftingRecipes().clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            var output = new ItemStack(Items.RECORD_CAT);
            thaumcraft.api.ThaumcraftApi.addCrucibleRecipe(new ResourceLocation("test", "empty_catalyst"),
                    new thaumcraft.api.crafting.CrucibleRecipe("", output, Ingredient.EMPTY, new AspectList().add(Aspect.FIRE, 25)));
            var crafting = new Recipe(output, new ItemStack(Items.APPLE));
            crafting.setRegistryName(new ResourceLocation("test", "must_not_replace_failed_crucible"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(crafting);
            var index = OrderedRecipeIndex.capture();
            var seed = state.checkpointTags();
            var expected = ThaumcraftCraftingManager.getObjectTags(output);
            state.restoreTags(seed);
            assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), output, 1)));
        }
    }

    @Test
    void oversizedIngredientListAndZeroCountOutputMatchOriginal() {
        try (var state = ThaumcraftState.cleared()) {
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
            var seed = state.checkpointTags();
            var zeroExpected = ThaumcraftCraftingManager.getObjectTags(new ItemStack(Items.RECORD_13));
            var oversizedExpected = ThaumcraftCraftingManager.getObjectTags(new ItemStack(Items.RECORD_11));
            assertEquals(Map.of(), values(zeroExpected));
            assertTrue(index.craftingCandidates(new ItemStack(Items.RECORD_13)).isEmpty());
            var cases = List.of(Map.entry(new ItemStack(Items.RECORD_13), zeroExpected),
                    Map.entry(new ItemStack(Items.RECORD_11), oversizedExpected));
            for (var entry : cases) {
                state.restoreTags(seed);
                assertEquals(values(entry.getValue()), values(evaluate(new AspectEvaluator(index), entry.getKey(), 1)));
            }
        }
    }

    @Test
    void wildcardMetadataFallbackDoesNotMutateCachedKeys() {
        try (var state = ThaumcraftState.cleared()) {
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.RECORD_13, 1, 10), new AspectList().add(Aspect.FIRE, 7));
            var query = new ItemStack(Items.RECORD_13, 1, 32767);
            var index = OrderedRecipeIndex.capture();
            var seed = state.checkpointTags();
            var expected = ThaumcraftCraftingManager.getObjectTags(query);
            state.restoreTags(seed);
            assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), query, 1)));

            CommonInternals.objectTags.clear();
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.RECORD_13, 1, 15), new AspectList().add(Aspect.WATER, 9));
            query.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
            seed = state.checkpointTags();
            expected = ThaumcraftCraftingManager.getObjectTags(query);
            state.restoreTags(seed);
            assertEquals(values(expected), values(evaluate(new AspectEvaluator(index), query, 1)));
        }
    }

    @Test
    void preparedPlansAreSharedAcrossDifferentNbtSessions() {
        try (var state = ThaumcraftState.cleared()) {
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.APPLE), new AspectList().add(Aspect.AIR, 20));
            var recipe = new Recipe(new ItemStack(Items.RECORD_FAR), new ItemStack(Items.APPLE));
            recipe.setRegistryName(new ResourceLocation("test", "shared_prepared_plan"));
            net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(recipe);
            var index = OrderedRecipeIndex.capture();
            var first = new ItemStack(Items.RECORD_FAR); first.getOrCreateSubCompound("test").setInteger("variant", 1);
            var second = new ItemStack(Items.RECORD_FAR); second.getOrCreateSubCompound("test").setInteger("variant", 2);
            var seed = state.checkpointTags();
            var firstExpected = ThaumcraftCraftingManager.getObjectTags(first);
            var secondExpected = ThaumcraftCraftingManager.getObjectTags(second);
            state.restoreTags(seed);
            recipe.ingredientCalls = recipe.remainingCalls = 0;
            var evaluator = new AspectEvaluator(index);
            assertEquals(values(firstExpected), values(evaluate(evaluator, first, 1)));
            assertEquals(values(secondExpected), values(evaluate(evaluator, second, 1)));
            assertEquals(1, recipe.ingredientCalls);
            assertEquals(1, recipe.remainingCalls);
        }
    }

    @Test
    void expiredDeadlineDoesNotAdvanceOrChangeTheResult() {
        try (var state = ThaumcraftState.cleared()) {
            var stack = new ItemStack(Items.APPLE);
            thaumcraft.api.ThaumcraftApi.registerObjectTag(stack, new AspectList().add(Aspect.AIR, 7));
            var session = new AspectEvaluator(OrderedRecipeIndex.capture()).session(stack);

            assertEquals(new AspectEvaluator.Running(0), session.advance(10, 0));
            assertEquals(0, session.progress().steps());
            assertEquals(1, session.progress().depth());
            assertEquals(Map.of("aer", 7), values(assertInstanceOf(AspectEvaluator.Complete.class,
                    session.advance(10, Long.MAX_VALUE)).aspects()));
        }
    }

    @Test
    void completedResultsOwnTheirAspectData() {
        var input = new AspectList().add(Aspect.AIR, 7);
        var complete = new AspectEvaluator.Complete(input);
        input.remove(Aspect.AIR);
        complete.aspects().remove(Aspect.AIR);
        assertEquals(Map.of("aer", 7), values(complete.aspects()));
    }

    @Test
    void longDependencyChainPreservesOriginalHistoryLimit() {
        try (var state = ThaumcraftState.cleared()) {
            int firstMetadata = 1_000;
            for (int offset = 0; offset < 110; offset++) {
                int metadata = firstMetadata + offset;
                var recipe = new Recipe(new ItemStack(Items.DYE, 1, metadata), new ItemStack(Items.DYE, 1, metadata + 1));
                recipe.setRegistryName(new ResourceLocation("test", "history_limit_" + metadata));
                net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(recipe);
            }
            thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(Items.DYE, 1, firstMetadata + 110),
                    new AspectList().add(Aspect.AIR, 200));
            var checkpoint = state.checkpointTags();
            var target = new ItemStack(Items.DYE, 1, firstMetadata);
            var expected = values(ThaumcraftCraftingManager.getObjectTags(target));
            state.restoreTags(checkpoint);
            var session = new AspectEvaluator(OrderedRecipeIndex.capture()).session(target);

            boundedSteps(session, 2_000);

            assertEquals(100, session.progress().historySize());
            assertEquals(expected, values(assertInstanceOf(AspectEvaluator.Complete.class,
                    session.advance(1, Long.MAX_VALUE)).aspects()));
        }
    }

    @Test
    void randomizedCraftingCandidatesMatchOriginalAcrossSliceSizes() {
        try (var state = ThaumcraftState.cleared()) {
            var random = new java.util.Random(20261025);
            var inputs = List.of(Items.APPLE, Items.CARROT, Items.DIAMOND);
            for (int sample = 0; sample < 24; sample++) {
                CommonInternals.objectTags.clear();
                for (var item : inputs) {
                    thaumcraft.api.ThaumcraftApi.registerObjectTag(new ItemStack(item),
                            new AspectList().add(Aspect.AIR, 1 + random.nextInt(100))
                                    .add(Aspect.FIRE, 1 + random.nextInt(100)));
                }
                var target = new ItemStack(Items.SKULL, 1, sample);
                for (int candidate = 0; candidate < 3; candidate++) {
                    var recipe = new Recipe(new ItemStack(Items.SKULL, 1 + random.nextInt(4), sample),
                            new ItemStack(inputs.get(random.nextInt(inputs.size()))),
                            new ItemStack(inputs.get(random.nextInt(inputs.size()))));
                    recipe.setRegistryName(new ResourceLocation("test", "random_" + sample + "_" + candidate));
                    net.minecraftforge.fml.common.registry.ForgeRegistries.RECIPES.register(recipe);
                }
                var checkpoint = state.checkpointTags();
                var index = OrderedRecipeIndex.capture();
                var expected = values(ThaumcraftCraftingManager.getObjectTags(target));
                for (int slice : new int[]{1, 3, 100}) {
                    state.restoreTags(checkpoint);
                    assertEquals(expected, values(evaluate(new AspectEvaluator(index), target, slice)),
                            "sample=" + sample + ", slice=" + slice);
                }
            }
        }
    }

    @Test
    void knownAndEmptyValuesMatchOriginal() {
        try (var state = ThaumcraftState.cleared()) {
            for (var item : new ItemStack[]{new ItemStack(Items.STICK), new ItemStack(Items.DIAMOND), ItemStack.EMPTY}) {
                CommonInternals.objectTags.clear();
                if (!item.isEmpty()) thaumcraft.api.ThaumcraftApi.registerObjectTag(item, new AspectList().add(Aspect.FIRE, 9));
                var evaluator = new AspectEvaluator(OrderedRecipeIndex.capture());
                assertEquals(values(ThaumcraftCraftingManager.getObjectTags(item)), values(evaluate(evaluator, item, 1)));
            }
        }
    }
}

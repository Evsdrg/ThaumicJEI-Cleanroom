package com.buuz135.thaumicjei.recipe;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class OrderedRecipeIndexTest {
    @BeforeAll static void bootstrap() { Bootstrap.register(); }

    private static final class FakeRecipe extends net.minecraftforge.registries.IForgeRegistryEntry.Impl<IRecipe> implements IRecipe {
        final ItemStack output;
        boolean fail;
        FakeRecipe(ItemStack output) { this.output = output; }
        @Override public boolean matches(InventoryCrafting inventory, World world) { return false; }
        @Override public ItemStack getCraftingResult(InventoryCrafting inventory) { return output.copy(); }
        @Override public boolean canFit(int width, int height) { return true; }
        @Override public ItemStack getRecipeOutput() {
            if (fail) throw new IllegalStateException("Dynamic output not available");
            return output;
        }
    }

    @Test
    void outputIdentityAndCandidateOrderMatchLinearScan() {
        var random = new Random(20261025);
        var recipes = new ArrayList<IRecipe>();
        for (int i = 0; i < 500; i++) recipes.add(new FakeRecipe(new ItemStack(Items.APPLE, 1,
                random.nextInt(5) == 0 ? 32767 : random.nextInt(5))));
        var index = OrderedRecipeIndex.from(recipes, List.of());
        for (int meta = 0; meta < 6; meta++) {
            int target = meta;
            var expected = recipes.stream().filter(recipe -> {
                int value = recipe.getRecipeOutput().getMetadata();
                return (value == 32767 ? 0 : value) == target;
            }).toList();
            var actual = index.craftingCandidates(new ItemStack(Items.APPLE, 1, meta));
            assertEquals(expected, actual.stream().map(OrderedRecipeIndex.Candidate::recipe).toList());
        }
        assertEquals(index.craftingCandidates(new ItemStack(Items.APPLE)),
                index.craftingCandidates(new ItemStack(Items.APPLE, 1, 32767)));
    }

    @Test
    void candidatesOwnOutputCopiesAndListsAreFrozen() {
        var output = new ItemStack(Items.APPLE, 2);
        var recipe = new FakeRecipe(output);
        var index = OrderedRecipeIndex.from(List.of(recipe), List.of());
        var candidates = index.craftingCandidates(output);
        output.setCount(9);
        assertEquals(2, candidates.getFirst().output().getCount());
        candidates.getFirst().output().setCount(33);
        assertEquals(2, candidates.getFirst().output().getCount());
        assertThrows(UnsupportedOperationException.class, candidates::clear);
        assertTrue(index.craftingCandidates(new ItemStack(Items.CARROT)).isEmpty());
        assertTrue(index.craftingCandidates(ItemStack.EMPTY).isEmpty());
    }

    @Test
    void thaumicMetadataDoesNotUseCraftingWildcardNormalization() {
        var wildcard = new thaumcraft.api.crafting.CrucibleRecipe("", new ItemStack(Items.APPLE, 1, 32767),
                new ItemStack(Items.CARROT), new thaumcraft.api.aspects.AspectList());
        var exact = new thaumcraft.api.crafting.CrucibleRecipe("", new ItemStack(Items.APPLE),
                new ItemStack(Items.CARROT), new thaumcraft.api.aspects.AspectList());
        var infusion = new thaumcraft.api.crafting.InfusionRecipe("", new ItemStack(Items.APPLE), 0,
                new thaumcraft.api.aspects.AspectList(), new ItemStack(Items.CARROT));
        var index = OrderedRecipeIndex.from(List.of(), List.of(wildcard, exact, infusion));
        assertEquals(List.of(wildcard, exact), index.crucibleRecipes());
        assertEquals(List.of(infusion), index.infusionRecipes());
        assertSame(exact, index.crucibleCandidates(new ItemStack(Items.APPLE)).getFirst().recipe());
        assertSame(wildcard, index.crucibleCandidates(new ItemStack(Items.APPLE, 1, 32767)).getFirst().recipe());
        assertSame(infusion, index.infusionCandidates(new ItemStack(Items.APPLE)).getFirst().recipe());
        assertThrows(UnsupportedOperationException.class, () -> index.crucibleRecipes().clear());
    }

    @Test
    void badOutputDoesNotDiscardOtherCandidates() {
        var broken = new FakeRecipe(ItemStack.EMPTY);
        broken.fail = true;
        var valid = new FakeRecipe(new ItemStack(Items.APPLE));
        var index = OrderedRecipeIndex.from(java.util.Arrays.asList(broken, null, new FakeRecipe(null), valid), List.of());
        assertEquals(1, index.unsupportedCount());
        assertSame(valid, index.craftingCandidates(new ItemStack(Items.APPLE)).getFirst().recipe());
        assertEquals(3, index.craftingCandidates(new ItemStack(Items.APPLE)).getFirst().ordinal());
    }
}

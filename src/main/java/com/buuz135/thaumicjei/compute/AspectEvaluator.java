package com.buuz135.thaumicjei.compute;

import com.buuz135.thaumicjei.recipe.OrderedRecipeIndex;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.oredict.OreDictionary;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.CrucibleRecipe;
import thaumcraft.api.crafting.IArcaneRecipe;
import thaumcraft.api.crafting.InfusionRecipe;
import thaumcraft.api.internal.CommonInternals;
import thaumcraft.common.lib.crafting.ContainerFake;
import thaumcraft.common.lib.crafting.ThaumcraftCraftingManager;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class AspectEvaluator {
    private static final int MAX_ASPECT_AMOUNT = 500;
    private static final int MAX_VISITED_ITEMS = 100;
    private static final Method BONUS_TAGS = findBonusTagsMethod();

    public sealed interface Outcome permits Running, Complete, Failed {}
    public record Running(int steps) implements Outcome {}
    public record Complete(AspectList aspects) implements Outcome {
        public Complete {
            aspects = aspects.copy();
        }

        @Override
        public AspectList aspects() {
            return aspects.copy();
        }
    }
    public record Failed(String reason) implements Outcome {}

    private enum Phase {
        INITIALIZE,
        TRY_CRUCIBLE,
        FINISH_CRUCIBLE,
        TRY_INFUSION,
        FINISH_INFUSION,
        NEXT_CRAFTING_CANDIDATE,
        FINISH_CRAFTING_CANDIDATE,
        NEXT_INPUT,
        ADD_INPUT_RESULT,
        NEXT_REMAINDER,
        SUBTRACT_REMAINDER_RESULT;

        boolean isCollectingIngredients() {
            return switch (this) {
                case NEXT_INPUT, ADD_INPUT_RESULT, NEXT_REMAINDER, SUBTRACT_REMAINDER_RESULT -> true;
                default -> false;
            };
        }
    }

    private final OrderedRecipeIndex recipeIndex;
    private final Map<Integer, AspectList> cachedAspects = new HashMap<>();
    private final Map<ItemStack, ItemStack> wildcardStacks = new HashMap<>();
    private final Map<ItemStack, Integer> exactItemIds = new HashMap<>();
    private final Map<ItemStack, Integer> strippedItemIds = new HashMap<>();
    private final Map<IRecipe, PreparedRecipe> craftingPlans = new IdentityHashMap<>();
    private final Map<CrucibleRecipe, PreparedRecipe> cruciblePlans = new IdentityHashMap<>();
    private final Map<InfusionRecipe, PreparedRecipe> infusionPlans = new IdentityHashMap<>();

    public AspectEvaluator(OrderedRecipeIndex recipeIndex) {
        this.recipeIndex = recipeIndex;
        CommonInternals.objectTags.forEach((key, value) -> cachedAspects.put(key, value.copy()));
    }

    private static Method findBonusTagsMethod() {
        try {
            var method = ThaumcraftCraftingManager.class.getDeclaredMethod("getBonusTags", ItemStack.class, AspectList.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Thaumcraft BETA26 bonus adapter unavailable", exception);
        }
    }

    private static AspectList withBonusTags(ItemStack stack, AspectList aspects) throws ReflectiveOperationException {
        return capAmounts((AspectList) BONUS_TAGS.invoke(null, stack, aspects));
    }

    private static AspectList capAmounts(AspectList aspects) {
        if (aspects == null) return null;
        var result = new AspectList();
        for (var aspect : aspects.getAspects()) {
            if (aspect != null) result.add(aspect, Math.min(MAX_ASPECT_AMOUNT, aspects.getAmount(aspect)));
        }
        return result;
    }

    private int exactId(ItemStack stack) {
        return exactItemIds.computeIfAbsent(stack, CommonInternals::generateUniqueItemstackId);
    }

    private int strippedId(ItemStack stack) {
        return strippedItemIds.computeIfAbsent(stack, CommonInternals::generateUniqueItemstackIdStripped);
    }

    private ItemStack wildcardStack(ItemStack stack) {
        return wildcardStacks.computeIfAbsent(stack, value -> {
            var copy = value.copy();
            copy.setItemDamage(OreDictionary.WILDCARD_VALUE);
            return copy;
        });
    }

    private AspectList findCachedAspects(ItemStack stack) {
        var found = cachedAspects.get(exactId(stack));
        if (found != null) return found.copy();
        var wildcard = wildcardStack(stack);
        found = cachedAspects.get(exactId(wildcard));
        if (found == null && stack.getMetadata() == OreDictionary.WILDCARD_VALUE) {
            var variant = wildcard.copy();
            for (int metadata = 0; metadata < 16 && found == null; metadata++) {
                variant.setItemDamage(metadata);
                found = cachedAspects.get(CommonInternals.generateUniqueItemstackId(variant));
            }
        }
        if (found == null) found = cachedAspects.get(strippedId(stack));
        if (found == null) found = cachedAspects.get(strippedId(wildcard));
        return found == null ? null : found.copy();
    }

    public Session session(ItemStack stack) {
        return new Session(stack.copy());
    }

    public final class Session {
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        // TC shares this history across sibling branches and never pops a failed child.
        private final Set<String> visitedItems = new HashSet<>();
        private AspectList childResult;
        private Outcome outcome = new Running(0);
        private int totalSteps;

        private Session(ItemStack stack) {
            frames.push(new Frame(stack));
        }

        public record Progress(int steps, int depth, int historySize) {}

        public Progress progress() {
            return new Progress(totalSteps, frames.size(), visitedItems.size());
        }

        public Outcome advance(int maxSteps, long deadlineNanos) {
            if (!(outcome instanceof Running)) return outcome;
            int used = 0;
            try {
                while (!frames.isEmpty() && used < maxSteps && System.nanoTime() < deadlineNanos) {
                    step(frames.peek());
                    used++;
                    totalSteps++;
                }
                outcome = currentOutcome();
            } catch (Exception | LinkageError exception) {
                recoverFromCallbackFailure();
            }
            return outcome;
        }

        private Outcome currentOutcome() {
            return frames.isEmpty() ? new Complete(childResult == null ? new AspectList() : childResult)
                    : new Running(totalSteps);
        }

        private void recoverFromCallbackFailure() {
            try {
                var frame = frames.peek();
                if (frame != null && frame.isEvaluatingCraftingCandidate()) {
                    frame.phase = Phase.NEXT_CRAFTING_CANDIDATE;
                    frame.accumulatedAspects = null;
                    frame.inputs = null;
                    frame.remainders = null;
                    childResult = null;
                } else {
                    childResult = frame == null ? null : withBonusTags(frame.originalStack, null);
                    if (frame != null) frames.pop();
                }
                outcome = currentOutcome();
            } catch (Exception | LinkageError failure) {
                frames.clear();
                outcome = new Failed(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
        }

        private void finish(Frame frame, AspectList aspects, boolean cacheResult) throws ReflectiveOperationException {
            if (cacheResult) {
                cachedAspects.put(exactId(frame.originalStack), aspects == null ? new AspectList() : capAmounts(aspects));
            }
            childResult = frame.originalStack.isEmpty() ? null : withBonusTags(frame.originalStack, aspects);
            frames.pop();
        }

        private void step(Frame frame) throws ReflectiveOperationException {
            switch (frame.phase) {
                case INITIALIZE -> initialize(frame);
                case TRY_CRUCIBLE -> tryCrucible(frame);
                case FINISH_CRUCIBLE -> finishCrucible(frame);
                case TRY_INFUSION -> tryInfusion(frame);
                case FINISH_INFUSION -> {
                    addRecipeAspects(frame.accumulatedAspects, frame.extraAspects, frame.outputCount);
                    finish(frame, removeNonPositiveAmounts(frame.accumulatedAspects), true);
                }
                case NEXT_CRAFTING_CANDIDATE -> nextCraftingCandidate(frame);
                case FINISH_CRAFTING_CANDIDATE -> finishCraftingCandidate(frame);
                case NEXT_INPUT -> nextInput(frame);
                case ADD_INPUT_RESULT -> {
                    if (childResult != null) frame.accumulatedAspects.add(childResult);
                    frame.phase = Phase.NEXT_INPUT;
                }
                case NEXT_REMAINDER -> nextRemainder(frame);
                case SUBTRACT_REMAINDER_RESULT -> {
                    if (childResult == null) throw new IllegalStateException("Null remaining-item aspects");
                    for (var aspect : childResult.getAspects()) {
                        frame.accumulatedAspects.reduce(aspect, childResult.getAmount(aspect));
                    }
                    frame.phase = Phase.NEXT_REMAINDER;
                }
            }
        }

        private void initialize(Frame frame) throws ReflectiveOperationException {
            if (frame.originalStack.isEmpty()) {
                childResult = null;
                frames.pop();
                return;
            }
            var cached = findCachedAspects(frame.originalStack);
            if (cached != null) {
                finish(frame, cached, false);
                return;
            }
            frame.recipeTarget = frame.originalStack.copy();
            frame.recipeTarget.setCount(1);
            if (frame.recipeTarget.getItem().isDamageable() || !frame.recipeTarget.getItem().getHasSubtypes()) {
                frame.recipeTarget.setItemDamage(OreDictionary.WILDCARD_VALUE);
            }
            String identity = frame.recipeTarget.serializeNBT().toString();
            if (!visitedItems.add(identity) || visitedItems.size() >= MAX_VISITED_ITEMS) {
                finish(frame, null, false);
                return;
            }
            if (frame.recipeTarget.getMetadata() == OreDictionary.WILDCARD_VALUE) frame.recipeTarget.setItemDamage(0);
            frame.phase = Phase.TRY_CRUCIBLE;
        }

        private void tryCrucible(Frame frame) {
            var candidates = recipeIndex.crucibleCandidates(frame.recipeTarget);
            if (!candidates.isEmpty()) {
                var candidate = candidates.getFirst();
                var plan = cruciblePlans.computeIfAbsent(candidate.recipe(),
                        recipe -> cruciblePlan(recipe, candidate.outputCount()));
                if (!plan.inputs().isEmpty()) {
                    frame.outputCount = plan.outputCount();
                    frame.extraAspects = plan.extraAspects();
                    frame.phase = Phase.FINISH_CRUCIBLE;
                    frames.push(new Frame(plan.inputs().getFirst()));
                    return;
                }
            }
            frame.phase = Phase.TRY_INFUSION;
        }

        private void finishCrucible(Frame frame) throws ReflectiveOperationException {
            var aspects = childResult == null ? new AspectList() : childResult.copy();
            addRecipeAspects(aspects, frame.extraAspects, frame.outputCount);
            finish(frame, removeNonPositiveAmounts(aspects), true);
        }

        private void tryInfusion(Frame frame) {
            var candidates = recipeIndex.infusionCandidates(frame.recipeTarget);
            if (!candidates.isEmpty()) {
                var candidate = candidates.getFirst();
                var plan = infusionPlans.computeIfAbsent(candidate.recipe(),
                        recipe -> infusionPlan(recipe, candidate.outputCount()));
                prepareIngredients(frame, plan, Phase.FINISH_INFUSION);
                return;
            }
            frame.craftingCandidates = recipeIndex.craftingCandidates(frame.recipeTarget);
            frame.phase = Phase.NEXT_CRAFTING_CANDIDATE;
        }

        private void nextCraftingCandidate(Frame frame) throws ReflectiveOperationException {
            if (frame.candidateIndex >= frame.craftingCandidates.size()) {
                finish(frame, frame.bestCraftingAspects, true);
                return;
            }
            var candidate = frame.craftingCandidates.get(frame.candidateIndex++);
            frame.craftingRecipe = candidate.recipe();
            try {
                var plan = craftingPlans.computeIfAbsent(candidate.recipe(),
                        recipe -> craftingPlan(recipe, candidate.outputCount()));
                prepareIngredients(frame, plan, Phase.FINISH_CRAFTING_CANDIDATE);
            } catch (RuntimeException exception) {
                frame.phase = Phase.NEXT_CRAFTING_CANDIDATE;
            }
        }

        private void finishCraftingCandidate(Frame frame) {
            var result = childResult == null ? new AspectList() : childResult.copy();
            int vis = frame.craftingRecipe instanceof IArcaneRecipe arcane ? arcane.getVis() : 0;
            if (vis > 0) {
                result.add(Aspect.MAGIC, (int) (Math.sqrt(1 + vis / 2) / (float) frame.outputCount));
            }
            removeNonPositiveAmounts(result);
            if (result.visSize() > 0
                    && (frame.bestCraftingAspects == null || result.visSize() < frame.bestCraftingAspects.visSize())) {
                frame.bestCraftingAspects = result.copy();
            }
            frame.accumulatedAspects = result;
            frame.phase = Phase.NEXT_CRAFTING_CANDIDATE;
        }

        private void nextInput(Frame frame) {
            if (frame.ingredientPosition < frame.inputs.size()) {
                var input = frame.inputs.get(frame.ingredientPosition++);
                if (!input.isEmpty()) {
                    frame.phase = Phase.ADD_INPUT_RESULT;
                    frames.push(new Frame(input));
                }
            } else {
                frame.ingredientPosition = 0;
                frame.phase = Phase.NEXT_REMAINDER;
            }
        }

        private void nextRemainder(Frame frame) {
            if (frame.ingredientPosition < frame.remainders.size()) {
                var remainder = frame.remainders.get(frame.ingredientPosition++);
                if (!remainder.isEmpty()) {
                    frame.phase = Phase.SUBTRACT_REMAINDER_RESULT;
                    frames.push(new Frame(remainder));
                }
            } else {
                frame.accumulatedAspects = scaleIngredientAspects(frame.accumulatedAspects, frame.outputCount);
                childResult = frame.accumulatedAspects;
                frame.phase = frame.afterIngredients;
            }
        }

        private static void prepareIngredients(Frame frame, PreparedRecipe plan, Phase nextPhase) {
            frame.inputs = plan.inputs();
            frame.remainders = plan.remainders();
            frame.extraAspects = plan.extraAspects();
            frame.outputCount = plan.outputCount();
            frame.ingredientPosition = 0;
            frame.accumulatedAspects = new AspectList();
            frame.afterIngredients = nextPhase;
            frame.phase = Phase.NEXT_INPUT;
        }
    }

    private static PreparedRecipe cruciblePlan(CrucibleRecipe recipe, int outputCount) {
        var extra = recipe.getAspects().copy();
        // TC uses the first match directly; an empty matching array follows its failure path.
        var catalyst = recipe.getCatalyst().getMatchingStacks()[0];
        return new PreparedRecipe(catalyst.isEmpty() ? List.of() : List.of(catalyst), List.of(), outputCount, extra);
    }

    private static PreparedRecipe infusionPlan(InfusionRecipe recipe, int outputCount) {
        var extra = recipe.getAspects().copy();
        var ingredients = new ArrayList<Ingredient>();
        ingredients.add(recipe.getRecipeInput());
        ingredients.addAll(recipe.getComponents());
        return prepareRecipe(ingredients, outputCount, null, extra);
    }

    private static PreparedRecipe craftingPlan(IRecipe recipe, int outputCount) {
        return prepareRecipe(new ArrayList<>(recipe.getIngredients()), outputCount, recipe, null);
    }

    private static PreparedRecipe prepareRecipe(List<Ingredient> ingredients, int outputCount,
                                                IRecipe recipe, AspectList extraAspects) {
        var inputs = new ArrayList<ItemStack>();
        var inventory = recipe == null ? null : new InventoryCrafting(new ContainerFake(), 3, 3);
        for (int slot = 0; slot < ingredients.size(); slot++) {
            var matches = ingredients.get(slot).getMatchingStacks();
            if (matches.length > 0) {
                var input = matches[0];
                inputs.add(input);
                if (recipe != null) inventory.setInventorySlotContents(slot, input);
            }
        }
        var remainders = recipe == null ? null : recipe.getRemainingItems(inventory);
        return new PreparedRecipe(inputs, remainders == null ? List.of() : remainders, outputCount, extraAspects);
    }

    private record PreparedRecipe(List<ItemStack> inputs, List<ItemStack> remainders, int outputCount,
                                  AspectList extraAspects) {
        private PreparedRecipe {
            inputs = List.copyOf(inputs);
            remainders = List.copyOf(remainders);
        }
    }

    private static void addRecipeAspects(AspectList accumulated, AspectList recipeAspects, int outputCount) {
        for (var aspect : recipeAspects.getAspects()) {
            accumulated.add(aspect, (int) (Math.sqrt(recipeAspects.getAmount(aspect)) / outputCount));
        }
    }

    private static AspectList scaleIngredientAspects(AspectList aspects, int outputCount) {
        var scaled = new AspectList();
        for (var aspect : aspects.getAspects()) {
            // Preserve TC's float arithmetic and truncation order.
            float amount = aspects.getAmount(aspect) * .75F / outputCount;
            if (amount < 1 && amount > .75D) amount = 1;
            scaled.add(aspect, (int) amount);
        }
        return removeNonPositiveAmounts(scaled);
    }

    private static AspectList removeNonPositiveAmounts(AspectList aspects) {
        for (var aspect : aspects.getAspects()) {
            if (aspects.getAmount(aspect) <= 0) aspects.remove(aspect);
        }
        return aspects;
    }

    private static final class Frame {
        final ItemStack originalStack;
        Phase phase = Phase.INITIALIZE;
        Phase afterIngredients;
        ItemStack recipeTarget;
        int outputCount;
        int ingredientPosition;
        int candidateIndex;
        IRecipe craftingRecipe;
        AspectList accumulatedAspects;
        AspectList extraAspects;
        AspectList bestCraftingAspects;
        List<ItemStack> inputs;
        List<ItemStack> remainders;
        List<OrderedRecipeIndex.Candidate<IRecipe>> craftingCandidates;

        Frame(ItemStack originalStack) {
            this.originalStack = originalStack;
        }

        boolean isEvaluatingCraftingCandidate() {
            return phase == Phase.FINISH_CRAFTING_CANDIDATE
                    || phase.isCollectingIngredients() && afterIngredients == Phase.FINISH_CRAFTING_CANDIDATE;
        }
    }
}

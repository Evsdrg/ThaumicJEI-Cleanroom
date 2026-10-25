package com.buuz135.thaumicjei.compute;

import com.buuz135.thaumicjei.recipe.OrderedRecipeIndex;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.IRecipe;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class AspectEvaluator {
    public sealed interface Outcome permits Running, Complete, Failed {}
    public record Running(int steps) implements Outcome {}
    public record Complete(AspectList aspects) implements Outcome {
        public Complete { aspects = aspects.copy(); }
        @Override public AspectList aspects() { return aspects.copy(); }
    }
    public record Failed(String reason) implements Outcome {}

    private static final Method BONUS = bonusMethod();
    private final OrderedRecipeIndex index;
    private final Map<Integer, AspectList> base = new HashMap<>();
    private final Map<ItemStack, ItemStack> wildcards = new HashMap<>();
    private final Map<ItemStack, Integer> ids = new HashMap<>();
    private final Map<ItemStack, Integer> strippedIds = new HashMap<>();
    private final Map<IRecipe, PreparedRecipe> craftingPlans = new IdentityHashMap<>();
    private final Map<CrucibleRecipe, PreparedRecipe> cruciblePlans = new IdentityHashMap<>();
    private final Map<InfusionRecipe, PreparedRecipe> infusionPlans = new IdentityHashMap<>();

    public AspectEvaluator(OrderedRecipeIndex index) {
        this.index = index;
        CommonInternals.objectTags.forEach((key, value) -> base.put(key, value.copy()));
    }

    private static Method bonusMethod() {
        try {
            var method = ThaumcraftCraftingManager.class.getDeclaredMethod("getBonusTags", ItemStack.class, AspectList.class);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Thaumcraft BETA26 bonus adapter unavailable", exception);
        }
    }

    private static AspectList bonus(ItemStack stack, AspectList value) throws ReflectiveOperationException {
        return cap((AspectList) BONUS.invoke(null, stack, value));
    }

    private static AspectList cap(AspectList value) {
        if (value == null) return null;
        var result = new AspectList();
        for (var aspect : value.getAspects()) if (aspect != null) result.add(aspect, Math.min(500, value.getAmount(aspect)));
        return result;
    }

    private int exactId(ItemStack stack) { return ids.computeIfAbsent(stack, CommonInternals::generateUniqueItemstackId); }
    private int strippedId(ItemStack stack) { return strippedIds.computeIfAbsent(stack, CommonInternals::generateUniqueItemstackIdStripped); }
    private ItemStack wildcard(ItemStack stack) {
        return wildcards.computeIfAbsent(stack, value -> { var copy = value.copy(); copy.setItemDamage(32767); return copy; });
    }
    private AspectList cached(ItemStack stack) {
        var found = base.get(exactId(stack));
        if (found != null) return found.copy();
        var wildcard = wildcard(stack);
        found = base.get(exactId(wildcard));
        if (found == null && stack.getMetadata() == 32767) {
            var variant = wildcard.copy();
            for (int meta = 0; meta < 16 && found == null; meta++) {
                variant.setItemDamage(meta);
                found = base.get(CommonInternals.generateUniqueItemstackId(variant));
            }
        }
        if (found == null) found = base.get(strippedId(stack));
        if (found == null) found = base.get(strippedId(wildcard(stack)));
        return found == null ? null : found.copy();
    }

    public Session session(ItemStack stack) { return new Session(stack.copy()); }

    public final class Session {
        private final ArrayDeque<Frame> frames = new ArrayDeque<>();
        private final ArrayList<String> history = new ArrayList<>();
        private final java.util.HashSet<String> historySet = new java.util.HashSet<>();
        private AspectList returned;
        private Outcome outcome = new Running(0);
        private int totalSteps;

        private Session(ItemStack stack) { frames.push(new Frame(stack)); }

        public record Progress(int steps, int depth, int historySize) {}

        public Progress progress() { return new Progress(totalSteps, frames.size(), history.size()); }

        public Outcome advance(int maxSteps, long deadlineNanos) {
            if (!(outcome instanceof Running)) return outcome;
            int used = 0;
            try {
                while (!frames.isEmpty() && used < maxSteps && System.nanoTime() < deadlineNanos) {
                    step(frames.peek()); used++; totalSteps++;
                }
                if (frames.isEmpty()) outcome = new Complete(returned == null ? new AspectList() : returned);
                else outcome = new Running(totalSteps);
            } catch (Exception | LinkageError exception) {
                try {
                    var frame = frames.peek();
                    if (frame != null && (frame.state == 6
                            || frame.state >= 10 && frame.state <= 13 && frame.afterIngredients == 6)) {
                        frame.state = 5; frame.value = null; frame.inputs = null; frame.remaining = null; returned = null;
                    } else {
                        returned = frame == null ? null : bonus(frame.original, null);
                        if (frame != null) frames.pop();
                    }
                    outcome = frames.isEmpty() ? new Complete(returned == null ? new AspectList() : returned) : new Running(totalSteps);
                } catch (Exception | LinkageError failure) {
                    frames.clear(); outcome = new Failed(failure.getClass().getSimpleName() + ": " + failure.getMessage());
                }
            }
            return outcome;
        }

        private void finish(Frame frame, AspectList value, boolean store) throws ReflectiveOperationException {
            if (store) base.put(exactId(frame.original), value == null ? new AspectList() : cap(value));
            returned = frame.original.isEmpty() ? null : bonus(frame.original, value);
            frames.pop();
        }

        private void step(Frame frame) throws ReflectiveOperationException {
            switch (frame.state) {
                case 0 -> {
                    if (frame.original.isEmpty()) { returned = null; frames.pop(); return; }
                    var cached = cached(frame.original);
                    if (cached != null) { finish(frame, cached, false); return; }
                    frame.target = frame.original.copy(); frame.target.setCount(1);
                    if (frame.target.getItem().isDamageable() || !frame.target.getItem().getHasSubtypes()) frame.target.setItemDamage(32767);
                    String key = frame.target.serializeNBT().toString();
                    if (historySet.contains(key)) { finish(frame, null, false); return; }
                    history.add(key); historySet.add(key);
                    if (history.size() >= 100) { finish(frame, null, false); return; }
                    if (frame.target.getMetadata() == 32767) frame.target.setItemDamage(0);
                    frame.state = 1;
                }
                case 1 -> {
                    var candidates = index.candidates(frame.target, OrderedRecipeIndex.Kind.CRUCIBLE);
                    if (!candidates.isEmpty()) {
                        var candidate = candidates.getFirst();
                        var recipe = (CrucibleRecipe) candidate.recipe();
                        var plan = cruciblePlans.computeIfAbsent(recipe, key -> cruciblePlan(key, candidate.outputCount()));
                        if (!plan.inputs().isEmpty()) {
                            frame.outputCount = plan.outputCount(); frame.extra = plan.extra();
                            frame.state = 2; frames.push(new Frame(plan.inputs().getFirst())); return;
                        }
                    }
                    frame.state = 3;
                }
                case 2 -> {
                    var value = returned == null ? new AspectList() : returned.copy();
                    addExtra(value, frame.extra, frame.outputCount);
                    finish(frame, clean(value), true);
                }
                case 3 -> {
                    var candidates = index.candidates(frame.target, OrderedRecipeIndex.Kind.INFUSION);
                    if (!candidates.isEmpty()) {
                        var candidate = candidates.getFirst();
                        var recipe = (InfusionRecipe) candidate.recipe();
                        var plan = infusionPlans.computeIfAbsent(recipe, key -> infusionPlan(key, candidate.outputCount()));
                        prepare(frame, plan, 4); return;
                    }
                    frame.candidates = index.candidates(frame.target, OrderedRecipeIndex.Kind.CRAFTING); frame.state = 5;
                }
                case 4 -> { addExtra(frame.value, frame.extra, frame.outputCount); finish(frame, clean(frame.value), true); }
                case 5 -> {
                    if (frame.candidate >= frame.candidates.size()) { finish(frame, frame.best, true); return; }
                    var candidate = frame.candidates.get(frame.candidate++);
                    var recipe = (IRecipe) candidate.recipe();
                    frame.recipe = recipe;
                    try {
                        var plan = craftingPlans.computeIfAbsent(recipe, key -> craftingPlan(key, candidate.outputCount()));
                        prepare(frame, plan, 6);
                    } catch (RuntimeException exception) { frame.state = 5; }
                }
                case 6 -> {
                    var result = returned == null ? new AspectList() : returned.copy();
                    int vis = frame.recipe instanceof IArcaneRecipe arcane ? arcane.getVis() : 0;
                    if (vis > 0) result.add(Aspect.MAGIC,
                            (int) (Math.sqrt(1 + vis / 2) / (float) frame.outputCount));
                    clean(result);
                    if (result.visSize() > 0 && (frame.best == null || result.visSize() < frame.best.visSize())) frame.best = result.copy();
                    frame.value = result;
                    frame.state = 5;
                }
                case 10 -> {
                    if (frame.position < frame.inputs.size()) {
                        var input = frame.inputs.get(frame.position++);
                        if (!input.isEmpty()) { frame.state = 11; frames.push(new Frame(input)); }
                    } else { frame.position = 0; frame.state = 12; }
                }
                case 11 -> { if (returned != null) frame.value.add(returned); frame.state = 10; }
                case 12 -> {
                    if (frame.position < frame.remaining.size()) {
                        var rest = frame.remaining.get(frame.position++);
                        if (!rest.isEmpty()) { frame.state = 13; frames.push(new Frame(rest)); }
                    } else {
                        var scaled = new AspectList();
                        for (var aspect : frame.value.getAspects()) {
                            float amount = frame.value.getAmount(aspect) * .75F / frame.outputCount;
                            if (amount < 1 && amount > .75D) amount = 1;
                            scaled.add(aspect, (int) amount);
                        }
                        frame.value = clean(scaled); returned = frame.value; frame.state = frame.afterIngredients;
                    }
                }
                case 13 -> {
                    if (returned == null) throw new IllegalStateException("Null remaining-item aspects");
                    for (var aspect : returned.getAspects()) frame.value.reduce(aspect, returned.getAmount(aspect));
                    frame.state = 12;
                }
                default -> throw new IllegalStateException("Unknown evaluator continuation");
            }
        }

        private static void prepare(Frame frame, PreparedRecipe plan, int next) {
            frame.inputs = plan.inputs(); frame.remaining = plan.remaining(); frame.extra = plan.extra();
            frame.outputCount = plan.outputCount(); frame.position = 0; frame.value = new AspectList();
            frame.afterIngredients = next; frame.state = 10;
        }
    }

    private static PreparedRecipe cruciblePlan(CrucibleRecipe recipe, int outputCount) {
        var extra = recipe.getAspects().copy();
        var catalyst = recipe.getCatalyst().getMatchingStacks()[0];
        return new PreparedRecipe(catalyst.isEmpty() ? List.of() : List.of(catalyst), List.of(), outputCount, extra);
    }

    private static PreparedRecipe infusionPlan(InfusionRecipe recipe, int outputCount) {
        var extra = recipe.getAspects().copy();
        var ingredients = new ArrayList<Ingredient>();
        ingredients.add(recipe.getRecipeInput()); ingredients.addAll(recipe.getComponents());
        return prepareRecipe(ingredients, outputCount, null, extra);
    }

    private static PreparedRecipe craftingPlan(IRecipe recipe, int outputCount) {
        return prepareRecipe(new ArrayList<>(recipe.getIngredients()), outputCount, recipe, null);
    }

    private static PreparedRecipe prepareRecipe(List<Ingredient> ingredients, int outputCount,
                                                IRecipe recipe, AspectList extra) {
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
        var remaining = recipe == null ? null : recipe.getRemainingItems(inventory);
        return new PreparedRecipe(inputs, remaining == null ? List.of() : remaining, outputCount, extra);
    }

    private record PreparedRecipe(List<ItemStack> inputs, List<ItemStack> remaining, int outputCount, AspectList extra) {
        private PreparedRecipe {
            inputs = List.copyOf(inputs);
            remaining = List.copyOf(remaining);
        }
    }

    private static void addExtra(AspectList value, AspectList extra, int count) {
        for (var aspect : extra.getAspects()) value.add(aspect, (int) (Math.sqrt(extra.getAmount(aspect)) / count));
    }
    private static AspectList clean(AspectList value) {
        for (var aspect : value.getAspects()) if (value.getAmount(aspect) <= 0) value.remove(aspect);
        return value;
    }
    private static final class Frame {
        final ItemStack original;
        ItemStack target;
        int state, outputCount, position, afterIngredients, candidate;
        IRecipe recipe;
        AspectList value, extra, best;
        List<ItemStack> inputs, remaining;
        List<OrderedRecipeIndex.Candidate> candidates;
        Frame(ItemStack original) { this.original = original; }
    }
}

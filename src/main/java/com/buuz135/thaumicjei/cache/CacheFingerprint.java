package com.buuz135.thaumicjei.cache;

import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.recipe.OrderedRecipeIndex;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.fml.common.Loader;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.CrucibleRecipe;
import thaumcraft.api.crafting.IArcaneRecipe;
import thaumcraft.api.crafting.InfusionRecipe;
import thaumcraft.api.internal.CommonInternals;
import thaumcraft.common.lib.crafting.ContainerFake;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CacheFingerprint {
    public record Source(List<String> fragments, List<Integer> sectionEnds, List<RecipeInput> recipes) {
        public Source {
            fragments = List.copyOf(fragments);
            sectionEnds = List.copyOf(sectionEnds);
            recipes = List.copyOf(recipes);
        }
        public Source(List<String> fragments) { this(fragments, List.of(), List.of()); }
    }
    public record RecipeInput(String id, String outputItem, List<String> inputs, List<String> fragments) {
        public RecipeInput { inputs = List.copyOf(inputs); fragments = List.copyOf(fragments); }
    }
    public record Result(String fingerprint, List<SnapshotStore.StoredRecipe> signatures, List<RecipeInput> inputs) {
        public Result { signatures = List.copyOf(signatures); inputs = List.copyOf(inputs); }
    }

    private enum Stage { MODS, TAGS, BLACKLIST, RECIPES, DONE }
    private static final List<String> STAGE_NAMES = List.of("mods", "tags", "blacklist");

    public static final class Preparer {
        private final List<String> fragments = new ArrayList<>();
        private final List<Integer> sectionEnds = new ArrayList<>();
        private final List<RecipeInput> recipes = new ArrayList<>();
        private final Set<String> recipeIds = new HashSet<>();
        private final List<String> mods;
        private final List<String> blacklist;
        private final List<OrderedRecipeIndex.Candidate> candidates;
        private final List<Map.Entry<Integer, AspectList>> tags;
        private Stage stage = Stage.MODS;
        private int position;

        private Preparer(OrderedRecipeIndex index, String[] blacklist) {
            mods = Loader.instance().getModList().stream()
                    .sorted(Comparator.comparing(mod -> mod.getModId()))
                    .map(mod -> mod.getModId() + ":" + mod.getVersion()).toList();
            this.blacklist = Arrays.stream(blacklist).sorted().toList();
            candidates = index.allCandidates();
            tags = CommonInternals.objectTags.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
            fragments.add("thaumicjei-schema-2-evaluator-5");
        }

        public Source advance(long deadlineNanos) {
            while (stage != Stage.DONE && System.nanoTime() < deadlineNanos) {
                switch (stage) {
                    case MODS, BLACKLIST -> add(stage == Stage.MODS ? mods : blacklist);
                    case TAGS -> {
                        if (position >= tags.size()) { nextStage(); break; }
                        var entry = tags.get(position++);
                        fragments.add("tag=" + entry.getKey()); addAspects(fragments, entry.getValue());
                    }
                    case RECIPES -> {
                        if (position >= candidates.size()) { nextStage(); break; }
                        addRecipe(candidates.get(position++));
                    }
                    case DONE -> { }
                }
            }
            return stage == Stage.DONE ? new Source(fragments, sectionEnds, recipes) : null;
        }

        public String progress() { return stage + ":" + position; }

        private void addRecipe(OrderedRecipeIndex.Candidate candidate) {
            String id = kind(candidate.recipe()) + ":" + candidate.id();
            if (!recipeIds.add(id)) throw new IllegalStateException("Duplicate recipe signature id: " + id);
            var fragments = new ArrayList<String>();
            var inputs = new ArrayList<String>();
            var output = candidate.output();
            String outputItem = itemName(output);
            try {
                fragments.add(candidate.recipe().getClass().getName());
                fragments.add(output.serializeNBT().toString());
                switch (candidate.recipe()) {
                    case IRecipe recipe -> {
                        var inventory = new InventoryCrafting(new ContainerFake(), 3, 3);
                        int slot = 0;
                        for (var ingredient : recipe.getIngredients()) {
                            var matches = ingredient.getMatchingStacks();
                            for (var stack : matches) {
                                fragments.add(stack.serializeNBT().toString()); inputs.add(itemName(stack));
                            }
                            if (matches.length > 0) inventory.setInventorySlotContents(slot, matches[0]);
                            slot++;
                        }
                        var remaining = recipe.getRemainingItems(inventory);
                        if (remaining == null) fragments.add("remaining:null");
                        else for (var stack : remaining) {
                            fragments.add("remaining:" + stack.serializeNBT()); inputs.add(itemName(stack));
                        }
                        if (recipe instanceof IArcaneRecipe arcane) {
                            fragments.add("vis=" + arcane.getVis()); addAspects(fragments, arcane.getCrystals());
                        }
                    }
                    case CrucibleRecipe recipe -> {
                        addAspects(fragments, recipe.getAspects());
                        for (var stack : recipe.getCatalyst().getMatchingStacks()) {
                            fragments.add(stack.serializeNBT().toString()); inputs.add(itemName(stack));
                        }
                    }
                    case InfusionRecipe recipe -> {
                        addAspects(fragments, recipe.getAspects());
                        for (var stack : recipe.getRecipeInput().getMatchingStacks()) {
                            fragments.add(stack.serializeNBT().toString()); inputs.add(itemName(stack));
                        }
                        for (var ingredient : recipe.getComponents()) for (var stack : ingredient.getMatchingStacks()) {
                            fragments.add(stack.serializeNBT().toString()); inputs.add(itemName(stack));
                        }
                    }
                    default -> { }
                }
            } catch (RuntimeException | LinkageError exception) {
                fragments.add("recipe-error:" + exception.getClass().getName());
            }
            recipes.add(new RecipeInput(id, outputItem, inputs, fragments));
        }

        private void add(List<String> values) {
            if (position < values.size()) fragments.add(values.get(position++));
            else nextStage();
        }

        private void nextStage() {
            if (stage != Stage.RECIPES) sectionEnds.add(fragments.size());
            stage = Stage.values()[stage.ordinal() + 1];
            position = 0;
        }
    }

    private CacheFingerprint() {}

    public static Preparer prepare(OrderedRecipeIndex index, String[] blacklist) {
        return new Preparer(index, blacklist);
    }

    public static Result capture(Source source) {
        try {
            writeDebug(source);
            int start = 0;
            for (int section = 0; section < source.sectionEnds().size(); section++) {
                int end = source.sectionEnds().get(section);
                if (end < start || end > source.fragments().size() || section >= STAGE_NAMES.size())
                    throw new IllegalArgumentException("Invalid fingerprint section boundaries");
                var digest = MessageDigest.getInstance("SHA-256");
                for (int i = start; i < end; i++) text(digest, source.fragments().get(i));
                if (ThaumicJEI.LOGGER != null) ThaumicJEI.LOGGER.info("Cache fingerprint section {}={}", STAGE_NAMES.get(section),
                        HexFormat.of().formatHex(digest.digest()));
                start = end;
            }
            if (!source.sectionEnds().isEmpty() && start != source.fragments().size())
                throw new IllegalArgumentException("Unhashed fingerprint fragments");
            var digest = MessageDigest.getInstance("SHA-256");
            for (var fragment : source.fragments()) text(digest, fragment);
            return new Result(HexFormat.of().formatHex(digest.digest()), signatures(source.recipes()), source.recipes());
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot establish a safe cache fingerprint", exception);
        }
    }

    static List<SnapshotStore.StoredRecipe> signatures(List<RecipeInput> recipes) {
        try {
            var result = new ArrayList<SnapshotStore.StoredRecipe>();
            for (var recipe : recipes) {
                var digest = MessageDigest.getInstance("SHA-256");
                for (var fragment : recipe.fragments()) text(digest, fragment);
                result.add(new SnapshotStore.StoredRecipe(recipe.id(), recipe.outputItem(),
                        HexFormat.of().formatHex(digest.digest())));
            }
            result.sort(Comparator.comparing(SnapshotStore.StoredRecipe::id));
            return List.copyOf(result);
        } catch (Exception exception) { throw new IllegalStateException("Cannot sign recipe", exception); }
    }

    public static Set<String> invalidatedItems(List<SnapshotStore.StoredRecipe> previous,
                                               List<SnapshotStore.StoredRecipe> current,
                                               List<RecipeInput> graph) {
        var before = byId(previous); var after = byId(current);
        var invalidated = new HashSet<String>();
        for (var id : union(before.keySet(), after.keySet())) {
            var oldRecipe = before.get(id); var newRecipe = after.get(id);
            if (oldRecipe == null || newRecipe == null || !oldRecipe.hash().equals(newRecipe.hash())
                    || !oldRecipe.outputItem().equals(newRecipe.outputItem())) {
                if (oldRecipe != null) invalidated.add(oldRecipe.outputItem());
                if (newRecipe != null) invalidated.add(newRecipe.outputItem());
            }
        }
        var dependents = new HashMap<String, Set<String>>();
        for (var recipe : graph) for (var input : recipe.inputs())
            dependents.computeIfAbsent(input, unused -> new HashSet<>()).add(recipe.outputItem());
        var queue = new ArrayDeque<>(invalidated);
        while (!queue.isEmpty()) {
            var changed = queue.removeFirst();
            for (var dependent : dependents.getOrDefault(changed, Set.of()))
                if (invalidated.add(dependent)) queue.addLast(dependent);
        }
        return Set.copyOf(invalidated);
    }

    private static Map<String, SnapshotStore.StoredRecipe> byId(List<SnapshotStore.StoredRecipe> recipes) {
        var result = new HashMap<String, SnapshotStore.StoredRecipe>();
        for (var recipe : recipes) if (result.put(recipe.id(), recipe) != null)
            throw new IllegalArgumentException("Duplicate recipe signature id: " + recipe.id());
        return result;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        var result = new HashSet<>(first); result.addAll(second); return result;
    }

    private static void writeDebug(Source source) throws java.io.IOException {
        var property = System.getProperty("thaumicjei.fingerprintDebug");
        if (property == null || property.isBlank()) return;
        var destination = Path.of(property).toAbsolutePath();
        Files.createDirectories(destination.getParent());
        Files.write(destination, source.fragments(), StandardCharsets.UTF_8);
    }

    private static String kind(Object recipe) {
        return switch (recipe) {
            case IRecipe ignored -> "crafting";
            case CrucibleRecipe ignored -> "crucible";
            case InfusionRecipe ignored -> "infusion";
            default -> "other";
        };
    }

    private static String itemName(ItemStack stack) { return String.valueOf(stack.getItem().getRegistryName()); }

    private static void addAspects(List<String> fragments, AspectList list) {
        if (list == null) { fragments.add("null"); return; }
        for (var aspect : Arrays.stream(list.getAspects()).sorted(Comparator.comparing(Aspect::getTag)).toList())
            fragments.add(aspect.getTag() + "=" + list.getAmount(aspect));
    }

    private static void text(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        int length = bytes.length;
        digest.update((byte) (length >>> 24)); digest.update((byte) (length >>> 16));
        digest.update((byte) (length >>> 8)); digest.update((byte) length);
        digest.update(bytes);
    }
}

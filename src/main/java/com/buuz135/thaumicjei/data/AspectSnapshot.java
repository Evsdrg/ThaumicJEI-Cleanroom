package com.buuz135.thaumicjei.data;

import net.minecraft.item.ItemStack;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AspectSnapshot {
    public static final int SOURCES_PER_PAGE = 36;
    public record Amount(Aspect aspect, int amount) {
        public Amount {
            Objects.requireNonNull(aspect);
            if (amount <= 0) throw new IllegalArgumentException("Amount must be positive");
        }
        public AspectList asList() { return new AspectList().add(aspect, amount); }
    }

    public sealed interface Lookup permits Unknown, Known {}
    public record Unknown() implements Lookup {}
    public record Known(List<Amount> amounts) implements Lookup {
        public Known { amounts = List.copyOf(amounts); }
    }
    public record Page(Aspect aspect, int offset, int size) {}
    public record PageKey(Aspect aspect, int index) {
        public PageKey {
            Objects.requireNonNull(aspect);
            if (index < 0) throw new IllegalArgumentException("Negative page index");
        }
    }
    public record PageChanges(List<PageKey> removals, List<Page> additions) {
        public PageChanges {
            removals = List.copyOf(removals);
            additions = List.copyOf(additions);
        }
    }
    public record Stats(int items, int relations, int aspects, long relationPayloadBytes) {}
    private record Range(int offset, int size) {}
    private record Row(int item, int amount) {}
    private record ItemEntry(ItemIdentity identity, Known value) {}

    private static final Unknown UNKNOWN = new Unknown();
    private final List<ItemEntry> items;
    private final Map<ItemIdentity, Integer> itemIds;
    private final Map<Aspect, Range> ranges;
    private final int[] sourceItems;
    private final int[] sourceAmounts;
    private final List<Page> pages;

    private AspectSnapshot(List<ItemEntry> items, Map<ItemIdentity, Integer> itemIds,
                           Map<Aspect, Range> ranges, int[] sourceItems, int[] sourceAmounts, List<Page> pages) {
        this.items = List.copyOf(items);
        this.itemIds = Map.copyOf(itemIds);
        this.ranges = Map.copyOf(ranges);
        this.sourceItems = sourceItems;
        this.sourceAmounts = sourceAmounts;
        this.pages = List.copyOf(pages);
    }

    public static Builder builder() { return new Builder(); }
    public static AspectSnapshot empty() { return builder().build(); }

    public Lookup lookup(ItemStack stack) {
        if (stack.isEmpty() || itemIds.isEmpty()) return UNKNOWN;
        var id = itemIds.get(ItemIdentity.of(stack));
        return id == null ? UNKNOWN : items.get(id).value();
    }

    public record StoredItem(String nbt, Map<String, Integer> aspects) {
        public StoredItem { aspects = Map.copyOf(aspects); }
    }

    public List<StoredItem> exportItems() {
        return items.stream().map(entry -> {
            var amounts = new LinkedHashMap<String, Integer>();
            entry.value().amounts().forEach(amount -> amounts.put(amount.aspect().getTag(), amount.amount()));
            return new StoredItem(entry.identity().serializedNbt(), amounts);
        }).toList();
    }

    public List<ItemStack> itemStacks() {
        return items.stream().map(entry -> entry.identity().createStack(1)).toList();
    }

    public List<Page> pages() { return pages; }

    public Page page(PageKey key) {
        var range = ranges.get(key.aspect());
        if (range == null) return null;
        long offset = range.offset() + (long) key.index() * SOURCES_PER_PAGE;
        long end = (long) range.offset() + range.size();
        if (offset >= end) return null;
        return new Page(key.aspect(), (int) offset, (int) Math.min(SOURCES_PER_PAGE, end - offset));
    }

    public PageKey pageKey(Page page) {
        var range = checkedRange(page);
        int relative = page.offset() - range.offset();
        var key = new PageKey(page.aspect(), relative / SOURCES_PER_PAGE);
        if (relative % SOURCES_PER_PAGE != 0 || !page(key).equals(page)) {
            throw new IllegalArgumentException("Page is not a snapshot boundary");
        }
        return key;
    }

    public PageChanges changesTo(AspectSnapshot next) {
        Objects.requireNonNull(next);
        var removals = new ArrayList<PageKey>();
        var additions = new ArrayList<Page>();
        for (var previous : pages) {
            var key = pageKey(previous);
            var replacement = next.page(key);
            if (replacement == null || !sameSources(this, previous, next, replacement)) removals.add(key);
        }
        for (var replacement : next.pages) {
            var key = next.pageKey(replacement);
            var previous = page(key);
            if (previous == null || !sameSources(next, replacement, this, previous)) additions.add(replacement);
        }
        return new PageChanges(removals, additions);
    }

    private static boolean sameSources(AspectSnapshot leftSnapshot, Page left, AspectSnapshot rightSnapshot, Page right) {
        if (left.size() != right.size()) return false;
        for (int position = 0; position < left.size(); position++) {
            int leftSource = left.offset() + position;
            int rightSource = right.offset() + position;
            var leftIdentity = leftSnapshot.items.get(leftSnapshot.sourceItems[leftSource]).identity();
            var rightIdentity = rightSnapshot.items.get(rightSnapshot.sourceItems[rightSource]).identity();
            if (leftSnapshot.sourceAmounts[leftSource] != rightSnapshot.sourceAmounts[rightSource]
                    || !leftIdentity.equals(rightIdentity)) return false;
        }
        return true;
    }

    private Range checkedRange(Page page) {
        var range = ranges.get(page.aspect());
        if (range == null || page.size() < 0 || page.size() > SOURCES_PER_PAGE || page.offset() < range.offset()
                || (long) page.offset() + page.size() > (long) range.offset() + range.size()) {
            throw new IllegalArgumentException("Source page does not belong to this snapshot");
        }
        return range;
    }

    public List<ItemStack> sourceStacks(Page page) {
        checkedRange(page);
        var stacks = new ArrayList<ItemStack>(page.size());
        for (int i = page.offset(); i < page.offset() + page.size(); i++) {
            stacks.add(items.get(sourceItems[i]).identity().createStack(sourceAmounts[i]));
        }
        return List.copyOf(stacks);
    }

    public Stats stats() {
        return new Stats(items.size(), sourceItems.length, ranges.size(),
                (long) sourceItems.length * Integer.BYTES * 2);
    }

    public static final class Builder {
        private final LinkedHashMap<ItemIdentity, LinkedHashMap<Aspect, Integer>> values = new LinkedHashMap<>();
        private final LinkedHashMap<Aspect, java.util.LinkedHashSet<ItemIdentity>> sourceOrder = new LinkedHashMap<>();

        public Builder add(ItemStack stack, Aspect aspect, int amount) {
            if (stack.isEmpty() || aspect == null || amount <= 0) return this;
            var identity = ItemIdentity.of(stack);
            values.computeIfAbsent(identity, unused -> new LinkedHashMap<>()).put(aspect, amount);
            sourceOrder.computeIfAbsent(aspect, unused -> new java.util.LinkedHashSet<>()).add(identity);
            return this;
        }

        public Builder knownEmpty(ItemStack stack) {
            if (!stack.isEmpty()) values.computeIfAbsent(ItemIdentity.of(stack), unused -> new LinkedHashMap<>());
            return this;
        }

        public AspectSnapshot build() {
            var items = new ArrayList<ItemEntry>(values.size());
            var ids = new LinkedHashMap<ItemIdentity, Integer>();
            var byAspect = new LinkedHashMap<Aspect, List<Row>>();
            for (var entry : values.entrySet()) {
                int id = items.size();
                var amounts = entry.getValue().entrySet().stream()
                        .map(value -> new Amount(value.getKey(), value.getValue()))
                        .sorted(Comparator.comparingInt(Amount::amount).reversed()).toList();
                items.add(new ItemEntry(entry.getKey(), new Known(amounts)));
                ids.put(entry.getKey(), id);
            }
            for (var entry : sourceOrder.entrySet()) {
                var rows = new ArrayList<Row>(entry.getValue().size());
                for (var identity : entry.getValue())
                    rows.add(new Row(ids.get(identity), values.get(identity).get(entry.getKey())));
                byAspect.put(entry.getKey(), rows);
            }
            int relations = byAspect.values().stream().mapToInt(List::size).sum();
            int[] sourceItems = new int[relations], sourceAmounts = new int[relations];
            var ranges = new LinkedHashMap<Aspect, Range>();
            var pages = new ArrayList<Page>();
            int offset = 0;
            for (var entry : byAspect.entrySet()) {
                var rows = entry.getValue();
                rows.sort(Comparator.comparingInt(Row::amount).reversed());
                ranges.put(entry.getKey(), new Range(offset, rows.size()));
                for (var row : rows) {
                    sourceItems[offset] = row.item();
                    sourceAmounts[offset++] = row.amount();
                }
                for (int start = offset - rows.size(); start < offset; start += SOURCES_PER_PAGE) {
                    pages.add(new Page(entry.getKey(), start, Math.min(SOURCES_PER_PAGE, offset - start)));
                }
            }
            return new AspectSnapshot(items, ids, ranges, sourceItems, sourceAmounts, pages);
        }
    }
}

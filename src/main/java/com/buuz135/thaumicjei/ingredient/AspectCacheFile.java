package com.buuz135.thaumicjei.ingredient;

import com.buuz135.thaumicjei.ThaumicJEI;
import com.buuz135.thaumicjei.config.ThaumicConfig;
import com.buuz135.thaumicjei.data.AspectSnapshot;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.JsonToNBT;
import thaumcraft.api.aspects.Aspect;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

public final class AspectCacheFile {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ITEMS = 250_000;

    private AspectCacheFile() {}

    public sealed interface LoadResult permits Loaded, Missing, Rejected {}
    public record Loaded(AspectSnapshot snapshot, int checked, int skipped) implements LoadResult {}
    public record Missing() implements LoadResult {}
    public record Rejected(String reason) implements LoadResult {}

    public static LoadResult load(Path path) {
        if (!Files.isRegularFile(path)) {
            ThaumicJEI.LOGGER.info("No item-aspect cache found; no automatic scan will run.");
            return new Missing();
        }
        var blacklist = new HashSet<>(Arrays.asList(ThaumicConfig.blacklistedFromAspectChecking));
        var builder = AspectSnapshot.builder();
        int checked = 0, skipped = 0;
        try {
            if (Files.size(path) > MAX_BYTES) throw new IOException("Item-aspect cache exceeds 64 MiB");
            try (var reader = new JsonReader(Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
                reader.setLenient(false);
                reader.beginArray();
                while (reader.hasNext()) {
                    String tag = null;
                    var serialized = new ArrayList<String>();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        switch (reader.nextName()) {
                            case "aspect" -> tag = reader.nextString();
                            case "items" -> {
                                reader.beginArray();
                                while (reader.hasNext()) {
                                    if (++checked > MAX_ITEMS) throw new IOException("Too many cached item-aspect entries");
                                    serialized.add(reader.nextString());
                                }
                                reader.endArray();
                            }
                            default -> reader.skipValue();
                        }
                    }
                    reader.endObject();
                    var aspect = tag == null ? null : Aspect.getAspect(tag);
                    if (aspect == null) { skipped += serialized.size(); continue; }
                    for (var value : serialized) {
                        try {
                            var nbt = JsonToNBT.getTagFromJson(value);
                            int amount = nbt.getShort("Count");
                            if (amount <= 0) { skipped++; continue; }
                            nbt.setByte("Count", (byte) 1);
                            var stack = new ItemStack(nbt);
                            if (stack.isEmpty() || blacklist.contains(stack.getItem().getRegistryName().toString())) {
                                skipped++;
                                continue;
                            }
                            builder.add(stack, aspect, amount);
                        } catch (Exception ignored) {
                            // A malformed or removed item must not discard other valid records.
                            skipped++;
                        }
                    }
                }
                reader.endArray();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing cache content");
            }
            var snapshot = builder.build();
            ThaumicJEI.LOGGER.info("Imported {} cache entries ({} skipped), {} unique items, {} relations.",
                    checked, skipped, snapshot.stats().items(), snapshot.stats().relations());
            return new Loaded(snapshot, checked, skipped);
        } catch (Exception exception) {
            ThaumicJEI.LOGGER.warn("Ignoring unreadable item-aspect cache; no automatic rebuild will run.", exception);
            return new Rejected(exception.getClass().getSimpleName());
        }
    }
}

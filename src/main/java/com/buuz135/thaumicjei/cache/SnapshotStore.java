package com.buuz135.thaumicjei.cache;

import com.buuz135.thaumicjei.data.AspectSnapshot;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class SnapshotStore {
    public static final int SCHEMA = 2;
    private static final long MAX_BYTES = 64L << 20;
    public record StoredRecipe(String id, String outputItem, String hash) {}
    public record Document(String fingerprint, List<AspectSnapshot.StoredItem> items, boolean complete,
                           List<StoredRecipe> recipes) {
        public Document {
            items = List.copyOf(items);
            recipes = List.copyOf(recipes);
        }
        public Document(String fingerprint, List<AspectSnapshot.StoredItem> items, boolean complete) {
            this(fingerprint, items, complete, List.of());
        }
    }
    public sealed interface ReadResult permits Hit, Miss, Invalid {}
    public record Hit(Document document) implements ReadResult {}
    public record Miss() implements ReadResult {}
    public record Invalid(String reason) implements ReadResult {}
    private SnapshotStore() {}

    public static ReadResult read(Path file) { return read(file, null); }

    public static ReadResult read(Path file, String expectedFingerprint) {
        if (!Files.isRegularFile(file)) return new Miss();
        try {
            if (Files.size(file) > MAX_BYTES) return new Invalid("File exceeds 64 MiB");
            int schema = -1; String fingerprint = "fixed-cache"; boolean complete = false, sawItems = false, sawComplete = false;
            var fields = new java.util.HashSet<String>();
            var items = new ArrayList<AspectSnapshot.StoredItem>();
            var recipes = new ArrayList<StoredRecipe>();
            try (var reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
                reader.setLenient(false); reader.beginObject();
                while (reader.hasNext()) {
                    var field = reader.nextName();
                    if (!fields.add(field)) throw new IOException("Duplicate document field");
                    switch (field) {
                    case "schema" -> schema = reader.nextInt();
                    case "fingerprint" -> fingerprint = reader.nextString();
                    case "complete" -> { complete = reader.nextBoolean(); sawComplete = true; }
                    case "items" -> {
                        sawItems = true;
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (items.size() >= 50_000) throw new IOException("Too many stored items");
                            String nbt = null; var amounts = new LinkedHashMap<String, Integer>();
                            reader.beginObject();
                            while (reader.hasNext()) switch (reader.nextName()) {
                                case "nbt" -> nbt = reader.nextString();
                                case "aspects" -> {
                                    reader.beginObject();
                                    while (reader.hasNext()) {
                                        if (amounts.size() >= 256) throw new IOException("Too many aspects");
                                        var tag = reader.nextName(); int amount = reader.nextInt();
                                        if (amount <= 0 || amount > 500) throw new IOException("Invalid aspect amount");
                                        amounts.put(tag, amount);
                                    }
                                    reader.endObject();
                                }
                                default -> reader.skipValue();
                            }
                            reader.endObject();
                            if (nbt == null || nbt.length() > 1_048_576) throw new IOException("Invalid item identity");
                            items.add(new AspectSnapshot.StoredItem(nbt, amounts));
                        }
                        reader.endArray();
                    }
                    case "recipes" -> {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (recipes.size() >= 50_000) throw new IOException("Too many stored recipes");
                            String id = null, output = null, hash = null;
                            reader.beginObject();
                            while (reader.hasNext()) switch (reader.nextName()) {
                                case "id" -> id = reader.nextString();
                                case "outputItem" -> output = reader.nextString();
                                case "hash" -> hash = reader.nextString();
                                default -> reader.skipValue();
                            }
                            reader.endObject();
                            if (id == null || id.isBlank() || id.length() > 512 || output == null || output.isBlank()
                                    || output.length() > 512 || hash == null || !hash.matches("[0-9a-f]{64}"))
                                throw new IOException("Invalid recipe signature");
                            recipes.add(new StoredRecipe(id, output, hash));
                        }
                        reader.endArray();
                    }
                    default -> reader.skipValue();
                    }
                }
                reader.endObject();
                if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("Trailing document content");
            }
            if (!sawItems || !sawComplete) return new Invalid("Missing cache contents or completion flag");
            var recipeIds = new java.util.HashSet<String>();
            for (var recipe : recipes) if (!recipeIds.add(recipe.id())) return new Invalid("Duplicate recipe signature");
            if (schema != SCHEMA) return new Invalid("Unsupported cache schema: " + schema);
            if (expectedFingerprint != null && !expectedFingerprint.equals(fingerprint)) return new Miss();
            return new Hit(new Document(fingerprint, items, complete, recipes));
        } catch (Exception exception) { return new Invalid(exception.getClass().getSimpleName()); }
    }

    private static void validate(Document document) throws IOException {
        if (!document.complete()) throw new IOException("Only complete caches may replace the fixed cache");
        if (document.fingerprint() == null || document.items().size() > 50_000 || document.recipes().size() > 50_000)
            throw new IOException("Invalid cache document");
        for (var item : document.items()) {
            if (item.nbt() == null || item.nbt().length() > 1_048_576 || item.aspects().size() > 256)
                throw new IOException("Invalid stored item");
            for (var entry : item.aspects().entrySet())
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                        || entry.getValue() <= 0 || entry.getValue() > 500) throw new IOException("Invalid aspect amount");
        }
        var ids = new java.util.HashSet<String>();
        for (var recipe : document.recipes())
            if (recipe.id() == null || recipe.id().isBlank() || recipe.id().length() > 512
                    || !ids.add(recipe.id()) || recipe.outputItem() == null || recipe.outputItem().isBlank()
                    || recipe.outputItem().length() > 512 || recipe.hash() == null || !recipe.hash().matches("[0-9a-f]{64}"))
                throw new IOException("Invalid recipe signature");
    }

    public static void write(Path file, Document document) throws IOException {
        write(file, document, () -> true);
    }

    public static void write(Path file, Document document, BooleanSupplier valid) throws IOException {
        write(file, document, valid, new Object());
    }

    public static void write(Path file, Document document, BooleanSupplier valid, Object commitLock) throws IOException {
        validate(document);
        if (!valid.getAsBoolean()) throw new IOException("Cache write cancelled");
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "thaumicjei-", ".part");
        try {
            try (var writer = new JsonWriter(Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))) {
                writer.beginObject(); writer.name("schema").value(SCHEMA);
                writer.name("fingerprint").value(document.fingerprint()); writer.name("complete").value(document.complete());
                writer.name("items").beginArray();
                for (var item : document.items()) {
                    if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) throw new IOException("Cache write cancelled");
                    writer.beginObject(); writer.name("nbt").value(item.nbt()); writer.name("aspects").beginObject();
                    for (var entry : item.aspects().entrySet()) writer.name(entry.getKey()).value(entry.getValue());
                    writer.endObject(); writer.endObject();
                }
                writer.endArray();
                writer.name("recipes").beginArray();
                for (var recipe : document.recipes()) {
                    if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) throw new IOException("Cache write cancelled");
                    writer.beginObject(); writer.name("id").value(recipe.id());
                    writer.name("outputItem").value(recipe.outputItem()); writer.name("hash").value(recipe.hash());
                    writer.endObject();
                }
                writer.endArray(); writer.endObject();
            }
            if (Files.size(temporary) > MAX_BYTES) throw new IOException("Generated cache exceeds 64 MiB");
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            synchronized (commitLock) {
                if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) throw new IOException("Cache write cancelled");
                try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException exception) { throw new IOException("Cache filesystem does not support atomic replacement", exception); }
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}

package com.buuz135.thaumicjei.cache;

import com.buuz135.thaumicjei.data.AspectSnapshot;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.BooleanSupplier;

public final class SnapshotStore {
    public static final int SCHEMA = 2;
    static final int MAX_ITEMS = 50_000;
    private static final int MAX_RECIPES = 50_000;
    private static final int MAX_ASPECTS_PER_ITEM = 256;
    private static final int MAX_ASPECT_AMOUNT = 500;
    private static final int MAX_ITEM_NBT_LENGTH = 1_048_576;
    private static final int MAX_RECIPE_ID_LENGTH = 512;
    private static final long MAX_BYTES = 64L << 20;

    public record StoredRecipe(String id, String outputItem, String hash) {}

    // Schema 2 retains signatures for files written before the fixed-cache policy.
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

    public static ReadResult read(Path file) {
        return read(file, null);
    }

    public static ReadResult read(Path file, String expectedFingerprint) {
        if (!Files.isRegularFile(file)) return new Miss();
        try {
            if (Files.size(file) > MAX_BYTES) return new Invalid("File exceeds 64 MiB");
            Document document;
            try (var reader = new JsonReader(Files.newBufferedReader(file, StandardCharsets.UTF_8))) {
                reader.setLenient(false);
                document = readDocument(reader);
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing document content");
            }
            if (expectedFingerprint != null && !expectedFingerprint.equals(document.fingerprint())) return new Miss();
            return new Hit(document);
        } catch (InvalidDocumentException exception) {
            return new Invalid(exception.getMessage());
        } catch (Exception exception) {
            return new Invalid(exception.getClass().getSimpleName());
        }
    }

    private static Document readDocument(JsonReader reader) throws IOException {
        int schema = -1;
        String fingerprint = "fixed-cache";
        boolean complete = false;
        boolean sawItems = false;
        boolean sawComplete = false;
        var fields = new HashSet<String>();
        var items = new ArrayList<AspectSnapshot.StoredItem>();
        var recipes = new ArrayList<StoredRecipe>();
        reader.beginObject();
        while (reader.hasNext()) {
            var field = reader.nextName();
            if (!fields.add(field)) throw invalid("Duplicate document field");
            switch (field) {
                case "schema" -> schema = reader.nextInt();
                case "fingerprint" -> fingerprint = reader.nextString();
                case "complete" -> {
                    complete = reader.nextBoolean();
                    sawComplete = true;
                }
                case "items" -> {
                    sawItems = true;
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (items.size() >= MAX_ITEMS) throw invalid("Too many stored items");
                        items.add(readStoredItem(reader));
                    }
                    reader.endArray();
                }
                case "recipes" -> {
                    reader.beginArray();
                    while (reader.hasNext()) {
                        if (recipes.size() >= MAX_RECIPES) throw invalid("Too many stored recipes");
                        recipes.add(readStoredRecipe(reader));
                    }
                    reader.endArray();
                }
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (!sawItems || !sawComplete) throw invalid("Missing cache contents or completion flag");
        var recipeIds = new HashSet<String>();
        for (var recipe : recipes) {
            if (!recipeIds.add(recipe.id())) throw invalid("Duplicate recipe signature");
        }
        if (schema != SCHEMA) throw invalid("Unsupported cache schema: " + schema);
        return new Document(fingerprint, items, complete, recipes);
    }

    private static AspectSnapshot.StoredItem readStoredItem(JsonReader reader) throws IOException {
        String nbt = null;
        var amounts = new LinkedHashMap<String, Integer>();
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "nbt" -> nbt = reader.nextString();
                case "aspects" -> readAspectAmounts(reader, amounts);
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        if (nbt == null || nbt.length() > MAX_ITEM_NBT_LENGTH) throw invalid("Invalid item identity");
        return new AspectSnapshot.StoredItem(nbt, amounts);
    }

    private static void readAspectAmounts(JsonReader reader, LinkedHashMap<String, Integer> amounts) throws IOException {
        reader.beginObject();
        while (reader.hasNext()) {
            if (amounts.size() >= MAX_ASPECTS_PER_ITEM) throw invalid("Too many aspects");
            var tag = reader.nextName();
            int amount = reader.nextInt();
            if (amount <= 0 || amount > MAX_ASPECT_AMOUNT) throw invalid("Invalid aspect amount");
            amounts.put(tag, amount);
        }
        reader.endObject();
    }

    private static StoredRecipe readStoredRecipe(JsonReader reader) throws IOException {
        String id = null;
        String output = null;
        String hash = null;
        reader.beginObject();
        while (reader.hasNext()) {
            switch (reader.nextName()) {
                case "id" -> id = reader.nextString();
                case "outputItem" -> output = reader.nextString();
                case "hash" -> hash = reader.nextString();
                default -> reader.skipValue();
            }
        }
        reader.endObject();
        var recipe = new StoredRecipe(id, output, hash);
        validateRecipe(recipe);
        return recipe;
    }

    private static void validate(Document document) throws IOException {
        if (!document.complete()) throw invalid("Only complete caches may replace the fixed cache");
        if (document.fingerprint() == null || document.items().size() > MAX_ITEMS
                || document.recipes().size() > MAX_RECIPES) throw invalid("Invalid cache document");
        for (var item : document.items()) {
            if (item.nbt() == null || item.nbt().length() > MAX_ITEM_NBT_LENGTH
                    || item.aspects().size() > MAX_ASPECTS_PER_ITEM) throw invalid("Invalid stored item");
            for (var entry : item.aspects().entrySet()) {
                if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                        || entry.getValue() <= 0 || entry.getValue() > MAX_ASPECT_AMOUNT) {
                    throw invalid("Invalid aspect amount");
                }
            }
        }
        var recipeIds = new HashSet<String>();
        for (var recipe : document.recipes()) {
            validateRecipe(recipe);
            if (!recipeIds.add(recipe.id())) throw invalid("Invalid recipe signature");
        }
    }

    private static void validateRecipe(StoredRecipe recipe) throws InvalidDocumentException {
        if (recipe.id() == null || recipe.id().isBlank() || recipe.id().length() > MAX_RECIPE_ID_LENGTH
                || recipe.outputItem() == null || recipe.outputItem().isBlank()
                || recipe.outputItem().length() > MAX_RECIPE_ID_LENGTH || recipe.hash() == null
                || !recipe.hash().matches("[0-9a-f]{64}")) throw invalid("Invalid recipe signature");
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
        var parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, "thaumicjei-", ".part");
        try {
            writeDocument(temporary, document, valid);
            if (Files.size(temporary) > MAX_BYTES) throw new IOException("Generated cache exceeds 64 MiB");
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            commitFile(temporary, file, valid, commitLock);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeDocument(Path file, Document document, BooleanSupplier valid) throws IOException {
        try (var writer = new JsonWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            writer.beginObject();
            writer.name("schema").value(SCHEMA);
            writer.name("fingerprint").value(document.fingerprint());
            writer.name("complete").value(document.complete());
            writer.name("items").beginArray();
            for (var item : document.items()) {
                checkWriteAllowed(valid);
                writeStoredItem(writer, item);
            }
            writer.endArray();
            writer.name("recipes").beginArray();
            for (var recipe : document.recipes()) {
                checkWriteAllowed(valid);
                writeStoredRecipe(writer, recipe);
            }
            writer.endArray();
            writer.endObject();
        }
    }

    private static void writeStoredItem(JsonWriter writer, AspectSnapshot.StoredItem item) throws IOException {
        writer.beginObject();
        writer.name("nbt").value(item.nbt());
        writer.name("aspects").beginObject();
        for (var entry : item.aspects().entrySet()) writer.name(entry.getKey()).value(entry.getValue());
        writer.endObject();
        writer.endObject();
    }

    private static void writeStoredRecipe(JsonWriter writer, StoredRecipe recipe) throws IOException {
        writer.beginObject();
        writer.name("id").value(recipe.id());
        writer.name("outputItem").value(recipe.outputItem());
        writer.name("hash").value(recipe.hash());
        writer.endObject();
    }

    private static void commitFile(Path temporary, Path destination, BooleanSupplier valid, Object commitLock)
            throws IOException {
        synchronized (commitLock) {
            checkWriteAllowed(valid);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Cache filesystem does not support atomic replacement", exception);
            }
        }
    }

    private static void checkWriteAllowed(BooleanSupplier valid) throws IOException {
        if (Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) throw new IOException("Cache write cancelled");
    }

    private static InvalidDocumentException invalid(String reason) {
        return new InvalidDocumentException(reason);
    }

    private static final class InvalidDocumentException extends IOException {
        private InvalidDocumentException(String reason) {
            super(reason);
        }
    }
}

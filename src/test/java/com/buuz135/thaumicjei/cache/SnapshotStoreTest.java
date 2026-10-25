package com.buuz135.thaumicjei.cache;

import com.buuz135.thaumicjei.data.AspectSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotStoreTest {
    @Test
    void atomicRoundTripAndFingerprintMiss(@TempDir Path directory) throws Exception {
        var file = directory.resolve("snapshot.json");
        var document = new SnapshotStore.Document("test", List.of(
                new AspectSnapshot.StoredItem("{id:\"minecraft:apple\",Count:1b}", Map.of("aer", 5)),
                new AspectSnapshot.StoredItem("{id:\"minecraft:stick\",Count:1b}", Map.of())), true,
                List.of(new SnapshotStore.StoredRecipe("test:recipe", "minecraft:apple", "0".repeat(64))));
        assertInstanceOf(SnapshotStore.Miss.class, SnapshotStore.read(file, "test"));
        SnapshotStore.write(file, document);
        var hit = assertInstanceOf(SnapshotStore.Hit.class, SnapshotStore.read(file, "test"));
        assertEquals(document, hit.document());
        assertInstanceOf(SnapshotStore.Miss.class, SnapshotStore.read(file, "changed"));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test
    void malformedAndUnknownSchemaAreNeverUsed(@TempDir Path directory) throws Exception {
        var file = directory.resolve("snapshot.json");
        for (var invalid : List.of("", "null", "[]", "{", "{\"schema\":1} trailing")) {
            Files.writeString(file, invalid);
            assertInstanceOf(SnapshotStore.Invalid.class, SnapshotStore.read(file, "test"));
        }
        Files.writeString(file, "{\"schema\":999,\"fingerprint\":\"test\",\"items\":[]}");
        assertInstanceOf(SnapshotStore.Invalid.class, SnapshotStore.read(file, "test"));
        Files.writeString(file, "{\"schema\":1,\"fingerprint\":\"test\",\"items\":[]}");
        assertInstanceOf(SnapshotStore.Invalid.class, SnapshotStore.read(file, "test"));
    }

    @Test
    void fixedReadIgnoresFingerprintAndRejectsMissingFields(@TempDir Path directory) throws Exception {
        var file = directory.resolve("snapshot.json");
        var document = new SnapshotStore.Document("old-environment", List.of(
                new AspectSnapshot.StoredItem("{id:\"minecraft:apple\",Count:1b}", Map.of("aer", 5))), true);
        SnapshotStore.write(file, document);
        assertEquals(document, assertInstanceOf(SnapshotStore.Hit.class, SnapshotStore.read(file)).document());
        byte[] previous = Files.readAllBytes(file);
        assertThrows(java.io.IOException.class, () -> SnapshotStore.write(file,
                new SnapshotStore.Document("partial", document.items(), false)));
        assertArrayEquals(previous, Files.readAllBytes(file));
        assertThrows(java.io.IOException.class, () -> SnapshotStore.write(file,
                new SnapshotStore.Document("bad", List.of(new AspectSnapshot.StoredItem("{}", Map.of("aer", 501))), true)));
        assertArrayEquals(previous, Files.readAllBytes(file));
        for (var text : List.of("{\"schema\":2}", "{\"schema\":2,\"complete\":true}",
                "{\"schema\":2,\"complete\":true,\"items\":[],\"complete\":false}")) {
            Files.writeString(file, text);
            assertInstanceOf(SnapshotStore.Invalid.class, SnapshotStore.read(file));
        }
    }

    @Test
    void invalidReasonsDescribeValidationWithoutEchoingInput(@TempDir Path directory) throws Exception {
        var file = directory.resolve("snapshot.json");
        Files.writeString(file, "{\"schema\":2,\"items\":[]}");
        assertEquals("Missing cache contents or completion flag",
                assertInstanceOf(SnapshotStore.Invalid.class, SnapshotStore.read(file)).reason());
        Files.writeString(file, "{\"schema\":2,\"complete\":true,\"items\":[],\"complete\":false}");
        assertEquals("Duplicate document field",
                assertInstanceOf(SnapshotStore.Invalid.class, SnapshotStore.read(file)).reason());
    }

    @Test
    void schemaTwoRecipeSignaturesRemainReadableForFixedImports(@TempDir Path directory) throws Exception {
        var file = directory.resolve("snapshot.json");
        var document = new SnapshotStore.Document("previous-policy", List.of(), true,
                List.of(new SnapshotStore.StoredRecipe("test:old", "minecraft:apple", "a".repeat(64))));
        SnapshotStore.write(file, document);
        assertEquals(document, assertInstanceOf(SnapshotStore.Hit.class, SnapshotStore.read(file)).document());
    }

    @Test
    void cancellationBeforeRenamePreservesPreviousDocument(@TempDir Path directory) throws Exception {
        var file = directory.resolve("snapshot.json");
        var previous = new SnapshotStore.Document("previous", List.of(
                new AspectSnapshot.StoredItem("{id:\"minecraft:apple\",Count:1b}", Map.of("aer", 5))), true);
        SnapshotStore.write(file, previous);
        var replacement = new SnapshotStore.Document("replacement", List.of(
                new AspectSnapshot.StoredItem("{id:\"minecraft:stick\",Count:1b}", Map.of())), true);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.io.IOException.class, () -> SnapshotStore.write(file, replacement,
                () -> checks.incrementAndGet() < 3));
        assertTrue(checks.get() >= 3);
        var hit = assertInstanceOf(SnapshotStore.Hit.class, SnapshotStore.read(file, "previous"));
        assertEquals(previous, hit.document());
        assertInstanceOf(SnapshotStore.Miss.class, SnapshotStore.read(file, "replacement"));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}

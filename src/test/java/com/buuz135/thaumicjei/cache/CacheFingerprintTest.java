package com.buuz135.thaumicjei.cache;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CacheFingerprintTest {
    @Test
    void globalFingerprintIsDeterministicAndTracksCapturedRules() {
        var fragments = List.of("schema", "base-aspect", "blacklist");
        var source = new CacheFingerprint.Source(fragments);
        String before = CacheFingerprint.capture(source).fingerprint();
        assertEquals(before, CacheFingerprint.capture(source).fingerprint());
        assertEquals(before, CacheFingerprint.capture(new CacheFingerprint.Source(fragments, List.of(0, fragments.size()), List.of())).fingerprint());
        assertNotEquals(before, CacheFingerprint.capture(new CacheFingerprint.Source(
                List.of("schema", "changed", "blacklist"))).fingerprint());
    }

    @Test
    void recipeChangesInvalidateOnlyTheirDependencyClosure() {
        var a = recipe("test:a", "test:a", "minecraft:x", "same");
        var b = recipe("test:b", "test:b", "test:a", "same");
        var c = recipe("test:c", "test:c", "minecraft:z", "same");
        var current = List.of(a, b, c);
        var previous = CacheFingerprint.signatures(current);
        assertEquals(Set.of(), CacheFingerprint.invalidatedItems(previous, previous, current));

        var changedA = recipe("test:a", "test:a", "minecraft:x", "changed");
        var changed = List.of(changedA, b, c);
        assertEquals(Set.of("test:a", "test:b"),
                CacheFingerprint.invalidatedItems(previous, CacheFingerprint.signatures(changed), changed));

        var removedA = List.of(b, c);
        assertEquals(Set.of("test:a", "test:b"),
                CacheFingerprint.invalidatedItems(previous, CacheFingerprint.signatures(removedA), removedA));
    }

    private static CacheFingerprint.RecipeInput recipe(String id, String output, String input, String value) {
        return new CacheFingerprint.RecipeInput(id, output, List.of(input), List.of(value));
    }
}

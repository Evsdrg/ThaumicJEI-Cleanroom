package com.buuz135.thaumicjei.cache;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GenerationStatusTest {
    @Test
    void readyStateDoesNotDependOnDisplayText() {
        var ready = status(GenerationStatus.Stage.READY, false, "ready; unavailable items or aspects skipped");
        assertTrue(ready.isReady());
        assertFalse(status(GenerationStatus.Stage.READY, true, "").isReady());
        assertFalse(status(GenerationStatus.Stage.GENERATING, false, "ready-looking diagnostic").isReady());
    }

    @Test
    void displayRetainsCommandCountersAndPauseLabel() {
        assertEquals("generating; completed=2, failed=1, queued=3, epoch=4",
                status(GenerationStatus.Stage.GENERATING, false, "").describe());
        assertTrue(status(GenerationStatus.Stage.GENERATING, true, "").describe().startsWith("paused;"));
    }

    private static GenerationStatus status(GenerationStatus.Stage stage, boolean paused, String detail) {
        return new GenerationStatus(stage, paused, 2, 1, 3, 4, detail, null);
    }
}

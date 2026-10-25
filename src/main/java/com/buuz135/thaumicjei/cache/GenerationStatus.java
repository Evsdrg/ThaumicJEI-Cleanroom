package com.buuz135.thaumicjei.cache;

import com.buuz135.thaumicjei.compute.AspectEvaluator;

public record GenerationStatus(Stage stage, boolean paused, int completed, int skippedEntries,
                               int queuedItems, long generation, String detail, Evaluation evaluation) {
    public enum Stage {
        IDLE("idle"),
        WAITING_FOR_RUNTIME("waiting for runtime"),
        READING_CACHE("reading fixed cache"),
        IMPORTING_CACHE("loading fixed cache"),
        GENERATING("generating"),
        SAVING_CACHE("saving complete fixed cache"),
        READY("ready"),
        CACHE_MISSING("cache missing; automatic generation disabled"),
        FAILED("failed"),
        CANCELLED("cancelled");

        private final String description;

        Stage(String description) {
            this.description = description;
        }
    }

    public record Evaluation(String item, int metadata, AspectEvaluator.Session.Progress progress) {}

    public boolean isReady() {
        return stage == Stage.READY && !paused;
    }

    public String describe() {
        String state;
        if (paused) state = "paused";
        else state = detail.isEmpty() ? stage.description : detail;
        String progress = evaluation == null ? "" : ", current=" + evaluation.item() + "@" + evaluation.metadata()
                + ", evaluation=" + evaluation.progress();
        return state + "; completed=" + completed + ", failed=" + skippedEntries + ", queued=" + queuedItems
                + ", epoch=" + generation + progress;
    }
}

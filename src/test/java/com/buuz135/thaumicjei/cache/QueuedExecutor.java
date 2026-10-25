package com.buuz135.thaumicjei.cache;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

final class QueuedExecutor extends AbstractExecutorService {
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private boolean shutdown;

    void runNext() {
        tasks.removeFirst().run();
    }

    @Override
    public void execute(Runnable task) {
        if (shutdown) throw new IllegalStateException("Executor is shut down");
        tasks.addLast(task);
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        var pending = List.copyOf(tasks);
        tasks.clear();
        return pending;
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    @Override
    public boolean isTerminated() {
        return shutdown && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }
}

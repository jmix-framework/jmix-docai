package com.company.docai.stand;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * An executor that runs each task in the scope its submitter works with, taken when the task is submitted: the scope
 * of the submitting thread, else the one of the browser whose command the thread runs. A task submitted without a
 * scope runs as it would without the stand.
 */
public class StandScopeExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    public StandScopeExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        StandScope scope = StandScopeHolder.resolve();

        delegate.execute(scope == null
                ? command
                : () -> StandScopeHolder.run(scope, command));
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}

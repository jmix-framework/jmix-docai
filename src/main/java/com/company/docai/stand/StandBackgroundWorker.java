package com.company.docai.stand;

import io.jmix.flowui.backgroundtask.impl.BackgroundWorkerImpl;

/**
 * The background tasks of the views on the stand, which work in the scope of the view that starts them: Jmix carries
 * the user of the view into the thread of a task, but not the scope.
 */
public class StandBackgroundWorker extends BackgroundWorkerImpl {

    @Override
    protected void createThreadPoolExecutor() {
        super.createThreadPoolExecutor();

        // Jmix keeps an executor it has created, so the executor is wrapped once
        if (!(executorService instanceof StandScopeExecutorService)) {
            executorService = new StandScopeExecutorService(executorService);
        }
    }
}

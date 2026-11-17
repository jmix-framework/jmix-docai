package com.company.docai.stand;

import io.jmix.core.impl.metadata.MetadataGenerationManager;
import io.jmix.flowui.asynctask.UiAsyncTaskProperties;
import io.jmix.flowui.asynctask.UiAsyncTasks;

import java.util.concurrent.ExecutorService;

/**
 * The asynchronous tasks of the views on the stand, such as the AI search, which work in the scope of the view that
 * starts them.
 */
public class StandUiAsyncTasks extends UiAsyncTasks {

    public StandUiAsyncTasks(UiAsyncTaskProperties uiAsyncTaskProperties,
                             MetadataGenerationManager metadataGenerationManager) {
        super(uiAsyncTaskProperties, metadataGenerationManager);
    }

    @Override
    protected ExecutorService createExecutorService() {
        return new StandScopeExecutorService(super.createExecutorService());
    }
}

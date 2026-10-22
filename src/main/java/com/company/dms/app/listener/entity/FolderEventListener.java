package com.company.dms.app.listener.entity;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.FetchPlan;
import io.jmix.core.Messages;
import io.jmix.core.event.EntityChangedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the folder tree whole on every save path: only the root has no parent,
 * and the root is never renamed, moved or deleted.
 */
@Component
public class FolderEventListener {

    private final DataManager dataManager;
    private final Messages messages;

    public FolderEventListener(DataManager dataManager, Messages messages) {
        this.dataManager = dataManager;
        this.messages = messages;
    }

    @EventListener
    public void onFolderChanged(EntityChangedEvent<Folder> event) {
        boolean root = Folder.ROOT_ID.equals(event.getEntityId().getValue());

        switch (event.getType()) {
            case CREATED -> checkHasParent(event);
            case UPDATED -> {
                if (root) {
                    throw reject("rootFolderLocked");
                }

                if (event.getChanges().isChanged("parent")) {
                    checkHasParent(event);
                }
            }
            case DELETED -> {
                if (root) {
                    throw reject("rootFolderLocked");
                }
            }
        }
    }

    private void checkHasParent(EntityChangedEvent<Folder> event) {
        Folder folder = dataManager.load(event.getEntityId())
                .fetchPlan(fetchPlan -> fetchPlan.add("parent", FetchPlan.INSTANCE_NAME))
                .one();

        if (folder.getParent() == null) {
            throw reject("folderWithoutParent");
        }
    }

    private OperationRejectedException reject(String key) {
        return new OperationRejectedException(messages.getMessage(getClass(), key));
    }
}

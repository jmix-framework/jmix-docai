package com.company.dms.app.listener.entity;

import com.company.dms.app.service.OperationRejectedException;
import com.company.dms.model.access.AccessEntry;
import com.company.dms.model.access.AccessSet;
import com.company.dms.model.document.Document;
import com.company.dms.model.folder.Folder;
import com.company.dms.model.user.User;
import io.jmix.core.*;
import io.jmix.core.event.EntityChangedEvent;
import io.jmix.core.event.EntitySavingEvent;
import io.jmix.data.PersistenceHints;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Keeps access whole on every save path: a new folder or document gets the access set of its parent,
 * a folder or a document refers to the set of its parent or to its own one and leaves its own one only
 * together with the set, an access entry is for exactly one subject, and the entries of a deleted user go
 * away with the user.
 */
@Component
public class AccessEventListener {

    // the rules hold whoever saves, also when the save hides the item from them
    private final UnconstrainedDataManager dataManager;
    private final Messages messages;

    public AccessEventListener(UnconstrainedDataManager dataManager, Messages messages) {
        this.dataManager = dataManager;
        this.messages = messages;
    }

    @EventListener
    public void onFolderSaving(EntitySavingEvent<Folder> event) {
        Folder folder = event.getEntity();
        if (event.isNewEntity() && folder.getAccessSet() == null && folder.getParent() != null) {
            folder.setAccessSet(accessSetOf(folder.getParent()));
        }
    }

    @EventListener
    public void onDocumentSaving(EntitySavingEvent<Document> event) {
        Document document = event.getEntity();
        if (event.isNewEntity() && document.getAccessSet() == null && document.getFolder() != null) {
            document.setAccessSet(accessSetOf(document.getFolder()));
        }
    }

    @EventListener
    public void onFolderChanged(EntityChangedEvent<Folder> event) {
        if (!accessMayHaveChanged(event, "parent")) {
            return;
        }

        Folder folder = dataManager.load(event.getEntityId())
                .fetchPlan(AccessEventListener::folderFetchPlan)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        boolean inherits = folder.getParent() != null
                && folder.getAccessSet().equals(folder.getParent().getAccessSet());

        if (!inherits && !folder.equals(folder.getAccessSet().getOwnerFolder())) {
            throw reject("accessSetNotInherited");
        }

        checkOwnSetGone(event, folder);
    }

    private static void folderFetchPlan(FetchPlanBuilder fetchPlan) {
        fetchPlan.add("accessSet", set -> set.add("ownerFolder", FetchPlan.INSTANCE_NAME))
                .add("parent", parent -> parent.add("accessSet", FetchPlan.INSTANCE_NAME));
    }

    @EventListener
    public void onDocumentChanged(EntityChangedEvent<Document> event) {
        if (!accessMayHaveChanged(event, "folder")) {
            return;
        }

        Document document = dataManager.load(event.getEntityId())
                .fetchPlan(AccessEventListener::documentFetchPlan)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .one();

        boolean inherits = document.getAccessSet().equals(document.getFolder().getAccessSet());

        if (!inherits && !document.equals(document.getAccessSet().getOwnerDocument())) {
            throw reject("accessSetNotInherited");
        }

        checkOwnSetGone(event, document);
    }

    private static void documentFetchPlan(FetchPlanBuilder fetchPlan) {
        fetchPlan.add("accessSet", set -> set.add("ownerDocument", FetchPlan.INSTANCE_NAME))
                .add("folder", folder -> folder.add("accessSet", FetchPlan.INSTANCE_NAME));
    }

    @EventListener
    public void onAccessEntryChanged(EntityChangedEvent<AccessEntry> event) {
        if (event.getType() == EntityChangedEvent.Type.DELETED) {
            return;
        }

        AccessEntry entry = dataManager.load(event.getEntityId()).one();
        if ((entry.getUsername() == null) == (entry.getRoleCode() == null)) {
            throw reject("accessEntryOneSubject");
        }
    }

    @EventListener
    public void onUserChanged(EntityChangedEvent<User> event) {
        if (event.getType() != EntityChangedEvent.Type.DELETED) {
            return;
        }

        String username = event.getChanges().getOldValue("username");
        List<AccessEntry> entries = dataManager.load(AccessEntry.class)
                .query("select ae from AccessEntry ae where ae.username = :username")
                .parameter("username", username)
                .list();

        dataManager.save(new SaveContext().removing(entries));
    }

    /**
     * An item leaves its own set only together with the set: what lies below the item still refers to the set,
     * and a set left behind would keep them out of the access of the parent.
     */
    private void checkOwnSetGone(EntityChangedEvent<?> event, Object item) {
        Id<AccessSet> previous = event.getChanges().getOldReferenceId("accessSet");
        if (previous == null) {
            return;
        }

        boolean stillOwned = dataManager.load(previous)
                .fetchPlan(AccessEventListener::ownerFetchPlan)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .optional()
                .filter(set -> item.equals(set.getOwnerFolder()) || item.equals(set.getOwnerDocument()))
                .isPresent();

        if (stillOwned) {
            throw reject("ownAccessSetLeft");
        }
    }

    private static void ownerFetchPlan(FetchPlanBuilder fetchPlan) {
        fetchPlan.add("ownerFolder", FetchPlan.INSTANCE_NAME)
                .add("ownerDocument", FetchPlan.INSTANCE_NAME);
    }

    private AccessSet accessSetOf(Folder folder) {
        return dataManager.load(Folder.class)
                .id(folder.getId())
                .fetchPlan(fetchPlan -> fetchPlan.add("accessSet", FetchPlan.INSTANCE_NAME))
                .one()
                .getAccessSet();
    }

    private static boolean accessMayHaveChanged(EntityChangedEvent<?> event, String parentAttribute) {
        return event.getType() == EntityChangedEvent.Type.CREATED
                || (event.getType() == EntityChangedEvent.Type.UPDATED
                && (event.getChanges().isChanged("accessSet") || event.getChanges().isChanged(parentAttribute)));
    }

    private OperationRejectedException reject(String key) {
        return new OperationRejectedException(messages.getMessage(getClass(), key));
    }
}

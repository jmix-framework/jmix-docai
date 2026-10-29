package com.company.dms.test_support;

import com.company.dms.model.document.DocumentComment;
import com.company.dms.model.folder.Folder;
import io.jmix.core.DataManager;
import io.jmix.core.SaveContext;
import io.jmix.data.PersistenceHints;

import java.util.List;

/**
 * Cleans the comments a test leaves: its sandbox goes to the trash, where a comment stays with its document until
 * the trash is emptied.
 */
public final class TestComments {

    private TestComments() {
    }

    /**
     * Deletes for good the comments on the documents of the folder and of its subfolders, the documents in the trash
     * included. Run it as the administrator.
     */
    public static void removeAll(DataManager dataManager, Folder folder) {
        List<DocumentComment> comments = dataManager.load(DocumentComment.class)
                .query("e.document.folder = :folder or e.document.folder.parent = :folder")
                .parameter("folder", folder)
                .hint(PersistenceHints.SOFT_DELETION, false)
                .list();

        dataManager.save(new SaveContext()
                .removing(comments)
                .setHint(PersistenceHints.SOFT_DELETION, false));
    }
}

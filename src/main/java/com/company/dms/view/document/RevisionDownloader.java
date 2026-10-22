package com.company.dms.view.document;

import com.company.dms.model.document.DocumentRevision;
import io.jmix.core.FileRef;
import io.jmix.core.FileStorageLocator;
import io.jmix.core.Messages;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.download.Downloader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Downloads a revision as an attachment, under the name it was uploaded with. When the file is gone
 * from the storage, the user gets the same message as in the preview instead of a broken download.
 */
@Component
public class RevisionDownloader {

    private final ObjectProvider<Downloader> downloaders;
    private final FileStorageLocator fileStorageLocator;
    private final Notifications notifications;
    private final Messages messages;

    public RevisionDownloader(ObjectProvider<Downloader> downloaders, FileStorageLocator fileStorageLocator,
                              Notifications notifications, Messages messages) {
        this.downloaders = downloaders;
        this.fileStorageLocator = fileStorageLocator;
        this.notifications = notifications;
        this.messages = messages;
    }

    /**
     * Sends the revision's file to the browser, or warns the user when the file is gone from the storage.
     */
    public void download(DocumentRevision revision) {
        FileRef file = revision.getFile();

        if (!fileStorageLocator.getByName(file.getStorageName()).fileExists(file)) {
            notifications.create(messages.formatMessage(getClass(), "fileMissing", revision.getNumber()))
                    .withType(Notifications.Type.WARNING)
                    .show();

            return;
        }

        // a downloader opens some types in a new tab unless told otherwise
        Downloader downloader = downloaders.getObject();
        downloader.setShowNewWindow(false);
        downloader.download(file);
    }
}

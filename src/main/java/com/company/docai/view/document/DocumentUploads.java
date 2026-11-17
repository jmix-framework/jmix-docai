package com.company.docai.view.document;

import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.document.UploadReport;
import com.company.docai.stand.StandUploadGuard;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.component.upload.UploadManager;
import io.jmix.core.Messages;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.upload.JmixUpload;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What the uploads of the repository and of the document card share: the largest file their upload components
 * take, on the stand the types of files they offer, the most files a batch upload takes at a time, the notices of a
 * file an upload refuses or fails to transfer, the notices of a file the drop zone of the repository refuses, and the
 * report of a batch upload.
 */
@Component
public class DocumentUploads {

    private static final int MAX_BATCH_FILES = 20;

    private final FilesProperties filesProperties;
    private final Notifications notifications;
    private final Dialogs dialogs;
    private final UiComponents uiComponents;
    private final Messages messages;
    private final ObjectProvider<StandUploadGuard> uploadGuard;

    public DocumentUploads(FilesProperties filesProperties, Notifications notifications, Dialogs dialogs,
                           UiComponents uiComponents, Messages messages,
                           ObjectProvider<StandUploadGuard> uploadGuard) {
        this.filesProperties = filesProperties;
        this.notifications = notifications;
        this.dialogs = dialogs;
        this.uiComponents = uiComponents;
        this.messages = messages;
        this.uploadGuard = uploadGuard;
    }

    /**
     * Limits the files the upload takes to the largest a document file may be and, on the stand, to the types a
     * visitor can upload there, so that its file picker offers only those.
     */
    public void limitFiles(JmixUpload<?> upload) {
        upload.setMaxFileSize((int) filesProperties.maxFileSize().toBytes());
        uploadGuard.ifAvailable(guard -> upload.setAcceptedFileExtensions(extensionsOf(guard)));
    }

    /**
     * Limits the files the upload manager of a batch upload takes: as many at a time as a batch takes, each up to the
     * largest a document file may be and, on the stand, of the types a visitor can upload there, so that the file
     * picker of the manager offers only those.
     */
    public void limitBatch(UploadManager manager) {
        manager.setMaxFiles(MAX_BATCH_FILES);
        manager.setMaxFileSize(filesProperties.maxFileSize().toBytes());
        uploadGuard.ifAvailable(guard -> manager.setAcceptedFileExtensions(extensionsOf(guard)));
    }

    /**
     * Shows the file the upload refused with the reason the browser gives.
     */
    public void showRejected(FileRejectedEvent event) {
        notifications.create(event.getFileName() + ": " + event.getErrorMessage())
                .withType(Notifications.Type.WARNING)
                .show();
    }

    /**
     * Shows that the transfer of the file broke off.
     */
    public void showFailed(String fileName) {
        notifications.create(messages.formatMessage(getClass(), "uploadFailed", fileName))
                .withType(Notifications.Type.ERROR)
                .show();
    }

    /**
     * Shows the file the drop zone refused with its reason: one file too many, a file too large, on the stand a type
     * a visitor cannot upload there, or another one.
     */
    public void showDropRejected(UploadManager.FileRejectedEvent event) {
        String key = switch (event.getReason()) {
            case TOO_MANY_FILES -> "dropRejected.tooManyFiles";
            case FILE_TOO_LARGE -> "dropRejected.fileTooLarge";
            default -> "dropRejected.other";
        };

        StandUploadGuard guard = uploadGuard.getIfAvailable();

        // the stand names the formats it takes, as it does for a file it refuses by its bytes
        String text = event.getReason() == UploadManager.FileRejectionReason.INCORRECT_FILE_TYPE && guard != null
                ? guard.typeRefusal(event.getFileName())
                : messages.formatMessage(getClass(), key, event.getFileName());

        notifications.create(text)
                .withType(Notifications.Type.WARNING)
                .show();
    }

    /**
     * Shows what a batch upload did: a notice with the number of new documents when it skipped no file, otherwise
     * a dialog that lists each skipped file with its reason on a line of its own under the summary.
     */
    public void showReport(UploadReport report) {
        if (report.skipped().isEmpty()) {
            notifications.create(messages.formatMessage(getClass(), "uploadDone", report.created().size()))
                    .withType(Notifications.Type.SUCCESS)
                    .show();

            return;
        }

        VerticalLayout content = uiComponents.create(VerticalLayout.class);
        content.setPadding(false);

        content.add(textLine(messages.formatMessage(getClass(), "uploadReport.summary",
                report.created().size(),
                report.skipped().size())));

        for (UploadReport.SkippedFile skipped : report.skipped()) {
            content.add(textLine(skipped.fileName() + " — " + skipped.reason()));
        }

        dialogs.createMessageDialog()
                .withHeader(messages.getMessage(getClass(), "uploadReport.header"))
                .withContent(content)
                .open();
    }

    // the extensions alone: the types the browser names files by differ between systems, and the stand checks a file
    // by its bytes anyway
    private static String[] extensionsOf(StandUploadGuard guard) {
        return guard.fileExtensions()
                .toArray(String[]::new);
    }

    private Span textLine(String text) {
        Span span = uiComponents.create(Span.class);
        span.setText(text);

        return span;
    }
}

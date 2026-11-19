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
 * What the uploads of the repository and of the card share: the limits of their files and the notices of refusals.
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
     * Limits the upload to the maximum document file size and, on the stand, to the file types a visitor can upload.
     */
    public void limitFiles(JmixUpload<?> upload) {
        upload.setMaxFileSize((int) filesProperties.maxFileSize().toBytes());
        uploadGuard.ifAvailable(guard -> upload.setAcceptedFileExtensions(extensionsOf(guard)));
    }

    /**
     * Limits the files the upload manager of a batch upload takes, as {@link #limitFiles} does, and how many at a time.
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
     * Shows the file the drop zone refused with its reason.
     */
    public void showDropRejected(UploadManager.FileRejectedEvent event) {
        String key = switch (event.getReason()) {
            case TOO_MANY_FILES -> "dropRejected.tooManyFiles";
            case FILE_TOO_LARGE -> "dropRejected.fileTooLarge";
            default -> "dropRejected.other";
        };

        StandUploadGuard guard = uploadGuard.getIfAvailable();

        String text = event.getReason() == UploadManager.FileRejectionReason.INCORRECT_FILE_TYPE && guard != null
                ? guard.typeRefusal(event.getFileName())
                : messages.formatMessage(getClass(), key, event.getFileName());

        notifications.create(text)
                .withType(Notifications.Type.WARNING)
                .show();
    }

    /**
     * Shows what a batch upload did: a notice, or a dialog that lists the reason of each skipped file.
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
            // the reasons of the services name the file themselves, as their notices do on a single upload
            content.add(textLine(skipped.reason()));
        }

        dialogs.createMessageDialog()
                .withHeader(messages.getMessage(getClass(), "uploadReport.header"))
                .withContent(content)
                .open();
    }

    // extensions only: browsers name the types of files differently on each system, and the stand checks bytes anyway
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

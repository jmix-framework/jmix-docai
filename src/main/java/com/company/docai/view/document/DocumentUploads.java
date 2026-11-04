package com.company.docai.view.document;

import com.company.docai.app.config.FilesProperties;
import com.company.docai.app.service.document.UploadReport;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.upload.FailedEvent;
import com.vaadin.flow.component.upload.FileRejectedEvent;
import com.vaadin.flow.component.upload.UploadManager;
import io.jmix.core.Messages;
import io.jmix.flowui.Dialogs;
import io.jmix.flowui.Notifications;
import io.jmix.flowui.UiComponents;
import io.jmix.flowui.component.upload.JmixUpload;
import org.springframework.stereotype.Component;

/**
 * What the uploads of the repository and of the document card share: the largest file their upload components
 * take, the notices of a file an upload refuses or fails to transfer, the notices of a file the drop zone of the
 * repository refuses, and the report of a batch upload.
 */
@Component
public class DocumentUploads {

    private final FilesProperties filesProperties;
    private final Notifications notifications;
    private final Dialogs dialogs;
    private final UiComponents uiComponents;
    private final Messages messages;

    public DocumentUploads(FilesProperties filesProperties, Notifications notifications, Dialogs dialogs,
                           UiComponents uiComponents, Messages messages) {
        this.filesProperties = filesProperties;
        this.notifications = notifications;
        this.dialogs = dialogs;
        this.uiComponents = uiComponents;
        this.messages = messages;
    }

    /**
     * Limits the files the upload takes to the largest a document file may be.
     */
    public void limitFileSize(JmixUpload<?> upload) {
        upload.setMaxFileSize((int) filesProperties.maxFileSize().toBytes());
    }

    /**
     * Limits the files the upload manager takes to the largest a document file may be.
     */
    public void limitFileSize(UploadManager manager) {
        manager.setMaxFileSize(filesProperties.maxFileSize().toBytes());
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
    public void showFailed(FailedEvent event) {
        notifications.create(messages.formatMessage(getClass(), "uploadFailed", event.getFileName()))
                .withType(Notifications.Type.ERROR)
                .show();
    }

    /**
     * Shows the file the drop zone refused with its reason: one file too many, a file too large, or another one.
     */
    public void showDropRejected(UploadManager.FileRejectedEvent event) {
        String key = switch (event.getReason()) {
            case TOO_MANY_FILES -> "dropRejected.tooManyFiles";
            case FILE_TOO_LARGE -> "dropRejected.fileTooLarge";
            default -> "dropRejected.other";
        };

        notifications.create(messages.formatMessage(getClass(), key, event.getFileName()))
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

    private Span textLine(String text) {
        Span span = uiComponents.create(Span.class);
        span.setText(text);

        return span;
    }
}

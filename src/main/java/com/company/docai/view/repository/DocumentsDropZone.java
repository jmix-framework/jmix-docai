package com.company.docai.view.repository;

import com.company.docai.app.service.document.UploadedFile;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.upload.UploadManager;
import com.vaadin.flow.dom.ElementUtil;
import com.vaadin.flow.server.streams.TemporaryFileUploadHandler;
import com.vaadin.flow.server.streams.TransferContext;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.server.streams.UploadMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The drop zone around the document table. Its own upload manager keeps the dropped files in the temporary directory
 * of the system until the drop ends or the zone leaves the page; the upload dialog of the list hands its files to the
 * same manager. A disabled zone would disable the table inside it, so only the manager is ever disabled, and a
 * strictly modal dialog opened over the page does not stop a drop on its way.
 */
public class DocumentsDropZone extends FileDropZone {

    private static final Logger log = LoggerFactory.getLogger(DocumentsDropZone.class);

    private final List<UploadedFile> droppedFiles = new ArrayList<>();

    private Runnable startHandler = () -> {
    };
    private Consumer<List<UploadedFile>> batchHandler = files -> {
    };
    private Consumer<UploadManager.FileRejectedEvent> rejectionHandler = event -> {
    };
    private Consumer<String> failureHandler = fileName -> {
    };

    /**
     * Creates the zone with its own upload manager.
     */
    public DocumentsDropZone() {
        TemporaryFileUploadHandler handler = UploadHandler.toTempFile(this::onFileDropped)
                .whenStart(() -> startHandler.run())
                .whenComplete(this::onTransferEnded);

        // a strictly modal dialog such as the document card makes the page behind it inert, and the server would then
        // refuse the rest of a batch on its way and never hear of its end; so the manager gets an owner of its own that
        // ignores the dialog, and only the enabled state of the manager decides what it takes
        Div owner = new Div();
        getElement().appendVirtualChild(owner.getElement());
        ElementUtil.setIgnoreParentInert(owner.getElement(), true);

        UploadManager manager = new UploadManager(owner, handler);
        manager.addFileRejectedListener(event -> rejectionHandler.accept(event));
        manager.addAllFinishedListener(event -> onDroppedFilesFinished());
        setUploadManager(manager);
    }

    /**
     * Receives the start of the transfer of each file, as the file leaves the browser: the first file of a drop starts
     * as the files are dropped.
     */
    public void setStartHandler(Runnable startHandler) {
        this.startHandler = startHandler;
    }

    /**
     * Receives the files of a drop when the last of them has arrived, an empty list when none has; the zone deletes
     * the files when the handler returns.
     */
    public void setBatchHandler(Consumer<List<UploadedFile>> batchHandler) {
        this.batchHandler = batchHandler;
    }

    /**
     * Receives a dropped file the manager refuses: one file too many, a file too large, or another reason.
     */
    public void setRejectionHandler(Consumer<UploadManager.FileRejectedEvent> rejectionHandler) {
        this.rejectionHandler = rejectionHandler;
    }

    /**
     * Receives the name of a file whose transfer breaks off; the file joins no drop, and nothing of it is kept.
     */
    public void setFailureHandler(Consumer<String> failureHandler) {
        this.failureHandler = failureHandler;
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);

        // leaving the page in the middle of a drop would leave its files in the temporary directory
        discardDroppedFiles();
    }

    private void onFileDropped(UploadMetadata metadata, File file) {
        droppedFiles.add(new UploadedFile(metadata.fileName(), file.toPath()));

        // a file still on its way when the user left the page joins no drop
        if (!isAttached()) {
            discardDroppedFiles();
        }
    }

    // the handler deletes what has arrived of a file whose transfer breaks off
    private void onTransferEnded(TransferContext context, boolean complete) {
        if (!complete) {
            failureHandler.accept(context.fileName());
        }
    }

    private void onDroppedFilesFinished() {
        getUploadManager().clearFileList();

        try {
            batchHandler.accept(List.copyOf(droppedFiles));
        } finally {
            discardDroppedFiles();
        }
    }

    // the zone keeps its files in the temporary directory of the system, not in the temporary storage of Jmix
    private void discardDroppedFiles() {
        for (UploadedFile file : droppedFiles) {
            try {
                Files.deleteIfExists(file.content());
            } catch (IOException e) {
                log.warn("A dropped file is left in the temporary directory: {}", file.content(), e);
            }
        }

        droppedFiles.clear();
    }
}

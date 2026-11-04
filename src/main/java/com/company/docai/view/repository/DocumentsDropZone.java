package com.company.docai.view.repository;

import com.company.docai.app.service.document.UploadedFile;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadManager;
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
 * A drop zone for files that Jmix sees into. Jmix looks for components by id and keeps their settings only inside
 * containers, so as a container the zone keeps the table it wraps within reach of the fragment and its settings.
 * Its own upload manager keeps the dropped files in the temporary directory of the system until the drop ends or the
 * zone leaves the page. A disabled zone would disable the table inside it, so only the manager is ever disabled.
 */
public class DocumentsDropZone extends UploadDropZone implements HasComponents {

    private static final Logger log = LoggerFactory.getLogger(DocumentsDropZone.class);

    private final List<UploadedFile> droppedFiles = new ArrayList<>();

    private Runnable arrivalHandler = () -> {
    };
    private Consumer<List<UploadedFile>> batchHandler = files -> {
    };
    private Consumer<UploadManager.FileRejectedEvent> rejectionHandler = event -> {
    };

    /**
     * Creates the zone with its own upload manager.
     */
    public DocumentsDropZone() {
        UploadManager manager = new UploadManager(this, UploadHandler.toTempFile(this::onFileDropped));
        manager.addFileRejectedListener(event -> rejectionHandler.accept(event));
        manager.addAllFinishedListener(event -> onDroppedFilesFinished());
        setUploadManager(manager);
    }

    /**
     * Receives the arrival of each dropped file, before the file joins its drop.
     */
    public void setArrivalHandler(Runnable arrivalHandler) {
        this.arrivalHandler = arrivalHandler;
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

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);

        configureDragEvents();
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);

        // a page reload or a session timeout leaves the files of an unfinished drop in the temporary directory
        discardDroppedFiles();
    }

    // The zone answers any drag, so the table passes on to it only the drags of files: its rows still go to the
    // folders and the favorites. The zone forgets the files only on a dragleave of its own, which never comes while
    // the table covers it, so it is told so when the files leave the table or the drag stops without a drop: the
    // browser repeats dragover while the files are over the table.
    private void configureDragEvents() {
        getElement()
                .executeJs("""
                        const zone = this;

                        if (zone.docaiDragEvents) {
                            return;
                        }

                        zone.docaiDragEvents = true;

                        for (const type of ['dragenter', 'dragover']) {
                            zone.querySelector('vaadin-grid').addEventListener(type, event => {
                                if (!event.dataTransfer || !Array.from(event.dataTransfer.types).includes('Files')) {
                                    event.stopPropagation();
                                }
                            });
                        }

                        let silence;

                        const forget = () => {
                            clearTimeout(silence);
                            zone.dispatchEvent(new DragEvent('dragleave'));
                        };

                        zone.addEventListener('dragover', () => {
                            clearTimeout(silence);
                            silence = setTimeout(forget, 700);
                        });

                        zone.addEventListener('dragleave', event => {
                            if (event.target !== zone && !zone.contains(event.relatedTarget)) {
                                forget();
                            }
                        });""");
    }

    private void onFileDropped(UploadMetadata metadata, File file) {
        arrivalHandler.run();
        droppedFiles.add(new UploadedFile(metadata.fileName(), file.toPath()));
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

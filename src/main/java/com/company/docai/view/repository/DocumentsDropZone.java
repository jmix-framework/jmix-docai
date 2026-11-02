package com.company.docai.view.repository;

import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.upload.UploadDropZone;
import com.vaadin.flow.component.upload.UploadManager;

/**
 * A drop zone for files that Jmix sees into. Jmix looks for components by id and keeps their settings only inside
 * containers, so as a container the zone keeps the table it wraps within reach of the fragment and its settings.
 */
public class DocumentsDropZone extends UploadDropZone implements HasComponents {

    /**
     * Creates the zone that hands the dropped files to the manager.
     *
     * @param manager the manager that uploads the files
     */
    public DocumentsDropZone(UploadManager manager) {
        super(manager);
    }
}

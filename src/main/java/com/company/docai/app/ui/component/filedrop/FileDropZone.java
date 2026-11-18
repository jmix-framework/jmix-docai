package com.company.docai.app.ui.component.filedrop;

import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.upload.UploadDropZone;

/**
 * A drop zone for files from the computer that Jmix sees into: Jmix looks for components by id and keeps their settings
 * only inside containers. In the browser it lights up only while files are dragged over it and takes no files while its
 * manager still holds those of a batch on its way.
 */
@Tag("docai-file-drop-zone")
@JsModule("./src/component/file-drop/docai-file-drop-zone.js")
public class FileDropZone extends UploadDropZone implements HasComponents {
}

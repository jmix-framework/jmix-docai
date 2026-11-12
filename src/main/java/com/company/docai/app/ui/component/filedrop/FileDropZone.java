package com.company.docai.app.ui.component.filedrop;

import com.vaadin.flow.component.HasComponents;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.upload.UploadDropZone;

/**
 * A drop zone for files from the computer that Jmix sees into. Jmix looks for components by id and keeps their
 * settings only inside containers, so as a container the zone keeps its content within reach of the view or the
 * fragment and of their settings. In the browser the element {@code docai-file-drop-zone} of
 * {@code src/component/file-drop/docai-file-drop-zone.js} extends the drop zone of Vaadin: the zone lights up only
 * while files are dragged over it, and it takes no files while its manager still holds those of a batch on its way.
 */
@Tag("docai-file-drop-zone")
@JsModule("./src/component/file-drop/docai-file-drop-zone.js")
public class FileDropZone extends UploadDropZone implements HasComponents {
}

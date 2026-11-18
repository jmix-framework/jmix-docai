package com.company.docai.app.ui.component.filedrop;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;

/**
 * The guard of the page against files dragged from the computer: they go only to the uploads that take them.
 */
@Tag("docai-file-drop-guard")
@JsModule("./src/component/file-drop/docai-file-drop-guard.js")
public class FileDropGuard extends Component {
}
